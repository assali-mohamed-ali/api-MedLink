package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AppointmentStatus;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.Slot;
import com.example.apimedlink.model.SlotStatus;
import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.repository.BookedAppointment;
import com.example.apimedlink.repository.ClinicRepository;
import com.example.apimedlink.repository.PractitionerRepository;
import com.example.apimedlink.repository.SlotRepository;
import com.example.apimedlink.service.SlotChange.Change;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A practitioner's agenda across the clinics, and the opening and closing of their slots (MLink-26). Takes and
 * returns entities and plain values, never a DTO. Everything is about the caller's own slots: the practitioner
 * id always comes from the session, never from the request.
 */
@Service
public class AgendaService {

    /** Assumptions of this task, not requirements of the cahier des charges: to confirm with the team. */
    static final Duration MIN_DURATION = Duration.ofMinutes(10);

    static final Duration MAX_DURATION = Duration.ofHours(8);
    static final Duration MAX_HORIZON = Duration.ofDays(180);

    /** All MedLink clinics are in Tunisia: messages show Tunis time whatever the server's time zone. */
    private static final ZoneId TUNIS = ZoneId.of("Africa/Tunis");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static final String ENDS_AT = "endsAt";

    /** The audit trail's name for a slot. */
    static final String RESOURCE_SLOT = "SLOT";

    private static final String SQLSTATE_EXCLUSION = "23P01";
    private static final String SQLSTATE_UNIQUE = "23505";
    private static final String SQLSTATE_FOREIGN_KEY = "23503";

    private final SlotRepository slots;
    private final AppointmentRepository appointments;
    private final ClinicRepository clinics;
    private final PractitionerRepository practitioners;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final AuditService audit;

    public AgendaService(SlotRepository slots, AppointmentRepository appointments, ClinicRepository clinics,
            PractitionerRepository practitioners, Clock clock, PlatformTransactionManager transactionManager,
            AuditService audit) {
        this.slots = slots;
        this.appointments = appointments;
        this.clinics = clinics;
        this.practitioners = practitioners;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.audit = audit;
    }

    /** The practitioner's clinics and their slots starting in {@code [from, to)}, past ones included. */
    @Transactional(readOnly = true)
    public Agenda agenda(UUID practitionerId, Instant from, Instant to) {
        requireProfile(practitionerId);
        List<Slot> found = slots.findAgenda(practitionerId, from, to);
        Map<UUID, BookedAppointment> booked = found.isEmpty() ? Map.of()
                : appointments.findWithPatient(found.stream().map(Slot::getId).toList(), AppointmentStatus.BOOKED)
                        .stream().collect(Collectors.toMap(BookedAppointment::slotId, Function.identity()));
        List<AgendaEntry> entries = found.stream()
                .map(slot -> new AgendaEntry(slot, booked.get(slot.getId()))).toList();
        return new Agenda(clinics.findOfPractitioner(practitionerId), entries);
    }

    /**
     * Opens a new slot. The overlap check is made first to name the slot in the way; the database constraint stays
     * the authority, so two requests racing for the same time still end with one slot and a clean 409.
     */
    public Slot open(UUID practitionerId, UUID clinicId, Instant startsAt, Instant endsAt) {
        validateNewSlot(startsAt, endsAt);
        requireProfile(practitionerId);
        Clinic clinic = clinics.findOfPractitioner(practitionerId).stream()
                .filter(own -> own.getId().equals(clinicId)).findFirst()
                .orElseThrow(AgendaService::clinicNotTheirs);
        for (int attempt = 1; ; attempt++) {
            List<Slot> inTheWay = slots.findOverlapping(practitionerId, startsAt, endsAt);
            if (!inTheWay.isEmpty()) {
                throw overlap(inTheWay.get(0));
            }
            try {
                // The slot and its audit row in one transaction: both are kept, or neither (review on #116)
                return transaction.execute(status -> {
                    Slot saved = slots.saveAndFlush(new Slot(practitioners.getReferenceById(practitionerId), clinic, startsAt, endsAt));
                    audit.recordWithin(AuditAction.SLOT_OPENED, RESOURCE_SLOT, saved.getId().toString(), AuditOutcome.SUCCESS);
                    return saved;
                });
            } catch (DataIntegrityViolationException refused) {
                // Outside the transaction that failed: the re-read below runs in a fresh one
                throw translate(refused, practitionerId, startsAt, endsAt);
            } catch (PessimisticLockingFailureException deadlock) {
                // Two overlapping openings at once: the exclusion constraint can make each insert wait for the
                // other, and Postgres aborts one with a deadlock (40P01) instead of an exclusion violation. The
                // other one won: try once more, the check above or the constraint then answers 409, never a 500.
                if (attempt >= 2) {
                    throw new ApiException(HttpStatus.CONFLICT, "SLOT_OVERLAP", "Vous avez déjà un créneau à ce moment.");
                }
            }
        }
    }

    /**
     * The slot if it is the caller's. A slot of someone else looks exactly like one that does not exist, and both
     * raise {@link AccessDeniedException}, so the refusal is audited and tells nothing about other people's slots.
     */
    @Transactional(readOnly = true)
    public Slot ownSlot(UUID practitionerId, UUID slotId) {
        return findOwn(practitionerId, slotId);
    }

    /** The lookup itself, for the methods of this class that already run in a transaction (no call through {@code this}). */
    private Slot findOwn(UUID practitionerId, UUID slotId) {
        return slots.findByIdAndPractitioner_Id(slotId, practitionerId)
                .orElseThrow(() -> new AccessDeniedException("Not the practitioner's slot"));
    }

    /**
     * Closes or reopens one of the caller's slots; the target is {@code FREE} (open) or {@code CLOSED}. A real change
     * writes its audit row in this same transaction (review on #116); nothing changed, nothing written.
     */
    @Transactional
    public SlotChange setStatus(UUID practitionerId, UUID slotId, SlotStatus target) {
        Slot slot = findOwn(practitionerId, slotId);
        if (slot.getStatus() == target) {
            return new SlotChange(slot, Change.NONE);
        }
        if (slot.getStatus() == SlotStatus.BOOKED) {
            throw booked();
        }
        return target == SlotStatus.CLOSED ? close(practitionerId, slotId) : reopen(practitionerId, slot);
    }

    private SlotChange close(UUID practitionerId, UUID slotId) {
        if (slots.closeIfFree(slotId, practitionerId) == 1) {
            audit.recordWithin(AuditAction.SLOT_CLOSED, RESOURCE_SLOT, slotId.toString(), AuditOutcome.SUCCESS);
            return new SlotChange(findOwn(practitionerId, slotId), Change.CLOSED);
        }
        // Nothing closed: a patient booked it in the meantime, or it was closed at the same moment
        Slot now = findOwn(practitionerId, slotId);
        if (now.getStatus() == SlotStatus.CLOSED) {
            return new SlotChange(now, Change.NONE);
        }
        if (now.getStatus() == SlotStatus.BOOKED
                || appointments.existsBySlot_IdAndStatus(slotId, AppointmentStatus.BOOKED)) {
            throw booked();
        }
        throw changedMeanwhile();
    }

    private SlotChange reopen(UUID practitionerId, Slot slot) {
        Instant now = clock.instant();
        if (!slot.getStartsAt().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "SLOT_IN_PAST",
                    "Ce créneau est déjà commencé ou passé : il ne peut plus être rouvert.");
        }
        if (slots.reopenIfClosed(slot.getId(), practitionerId, now) == 1) {
            audit.recordWithin(AuditAction.SLOT_OPENED, RESOURCE_SLOT, slot.getId().toString(), AuditOutcome.SUCCESS);
            return new SlotChange(findOwn(practitionerId, slot.getId()), Change.OPENED);
        }
        Slot current = findOwn(practitionerId, slot.getId());
        if (current.getStatus() == SlotStatus.FREE) {
            return new SlotChange(current, Change.NONE);
        }
        throw changedMeanwhile();
    }

    private void requireProfile(UUID practitionerId) {
        if (!practitioners.existsById(practitionerId)) {
            throw new ApiException(HttpStatus.CONFLICT, "PRACTITIONER_PROFILE_MISSING",
                    "Votre profil praticien n'est pas encore configuré. Contactez l'administration.");
        }
    }

    private void validateNewSlot(Instant startsAt, Instant endsAt) {
        Instant now = clock.instant();
        Map<String, String> errors = new LinkedHashMap<>();
        if (!endsAt.isAfter(startsAt)) {
            errors.put(ENDS_AT, "La fin doit être après le début.");
        } else if (Duration.between(startsAt, endsAt).compareTo(MIN_DURATION) < 0) {
            errors.put(ENDS_AT, "Un créneau dure au moins " + MIN_DURATION.toMinutes() + " minutes.");
        } else if (Duration.between(startsAt, endsAt).compareTo(MAX_DURATION) > 0) {
            errors.put(ENDS_AT, "Un créneau dure au plus " + MAX_DURATION.toHours() + " heures.");
        }
        if (!startsAt.isAfter(now)) {
            errors.put("startsAt", "Le début doit être dans le futur.");
        } else if (Duration.between(now, startsAt).compareTo(MAX_HORIZON) > 0) {
            errors.put("startsAt", "Un créneau ne peut pas être ouvert plus de " + MAX_HORIZON.toDays() + " jours à l'avance.");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.", errors);
        }
    }

    /** The database refused the slot: turn what we know how to explain into an ApiException, rethrow the rest. */
    private RuntimeException translate(DataIntegrityViolationException refused, UUID practitionerId, Instant startsAt,
            Instant endsAt) {
        String sqlState = sqlState(refused);
        if (SQLSTATE_EXCLUSION.equals(sqlState) || SQLSTATE_UNIQUE.equals(sqlState)) {
            List<Slot> inTheWay = slots.findOverlapping(practitionerId, startsAt, endsAt);
            return inTheWay.isEmpty()
                    ? new ApiException(HttpStatus.CONFLICT, "SLOT_OVERLAP", "Vous avez déjà un créneau à ce moment.")
                    : overlap(inTheWay.get(0));
        }
        if (SQLSTATE_FOREIGN_KEY.equals(sqlState)) {
            return clinicNotTheirs();
        }
        return refused;
    }

    private static String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "";
    }

    /** The conflict is always one of the caller's own slots, so naming its clinic and time exposes nobody else. */
    private static ApiException overlap(Slot taken) {
        String when = "de " + TIME.format(taken.getStartsAt().atZone(TUNIS)) + " à "
                + TIME.format(taken.getEndsAt().atZone(TUNIS)) + " à " + taken.getClinic().getName() + " le "
                + DAY.format(taken.getStartsAt().atZone(TUNIS));
        boolean closed = taken.getStatus() == SlotStatus.CLOSED;
        String message = closed
                ? "Un créneau fermé occupe déjà ce moment (" + when + ") : rouvrez-le."
                : "Vous avez déjà un créneau " + when + ".";
        Map<String, String> details = new LinkedHashMap<>();
        details.put("conflictClinic", taken.getClinic().getName());
        details.put("conflictStartsAt", taken.getStartsAt().toString());
        details.put("conflictEndsAt", taken.getEndsAt().toString());
        details.put("conflictStatus", apiStatus(taken.getStatus()));
        return new ApiException(HttpStatus.CONFLICT, "SLOT_OVERLAP", message, details);
    }

    /** The status as the API says it: {@code OPEN} where the database says {@code FREE}. */
    private static String apiStatus(SlotStatus status) {
        return switch (status) {
            case FREE -> "OPEN";
            case BOOKED -> "BOOKED";
            case CLOSED -> "CLOSED";
        };
    }

    private static ApiException clinicNotTheirs() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.",
                Map.of("clinicId", "Choisissez l'une de vos cliniques."));
    }

    private static ApiException booked() {
        return new ApiException(HttpStatus.CONFLICT, "SLOT_BOOKED",
                "Ce créneau est réservé par un patient : il ne peut pas être modifié.");
    }

    private static ApiException changedMeanwhile() {
        return new ApiException(HttpStatus.CONFLICT, "SLOT_CHANGED",
                "Le créneau vient d'être modifié. Actualisez l'agenda et réessayez.");
    }
}
