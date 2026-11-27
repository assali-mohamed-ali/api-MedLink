package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AppointmentStatus;
import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.Practitioner;
import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.repository.ClinicRepository;
import com.example.apimedlink.repository.PractitionerRepository;
import java.text.Collator;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import com.example.apimedlink.model.SlotStatus;
import com.example.apimedlink.repository.SlotRepository;
import org.springframework.transaction.annotation.Isolation;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MLink-35: what staff read to supervise the clinics, never change: the practitioners and any one's agenda. The
 * agenda is the practitioner's own (AgendaService), so staff see exactly what the practitioner sees, minus the actions.
 */
@Service
public class ClinicActivityService {

    private final PractitionerRepository practitioners;
    private final AgendaService agendas;
    private final ClinicRepository clinics;
    private final AppointmentRepository appointments;
    private final SlotRepository slots;

    public ClinicActivityService(PractitionerRepository practitioners, AgendaService agendas,
            ClinicRepository clinics, AppointmentRepository appointments, SlotRepository slots) {
        this.practitioners = practitioners;
        this.agendas = agendas;
        this.clinics = clinics;
        this.appointments = appointments;
        this.slots = slots;
    }

    @Transactional(readOnly = true)
    public List<Clinic> clinics() {
        return clinics.findAll(Sort.by("name", "id"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Long> activity(UUID clinicId, Instant from, Instant to) {
        if (!clinics.existsById(clinicId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CLINIC_NOT_FOUND", "Cette clinique n'existe pas.");
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AppointmentStatus status : AppointmentStatus.values()) {
            counts.put(status.name(), 0L);
        }
        appointments.countActivity(clinicId, from, to)
                .forEach(count -> counts.put(count.getStatus().name(), count.getTotal()));
        Map<SlotStatus, Long> capacity = new java.util.EnumMap<>(SlotStatus.class);
        slots.countClinicCapacity(clinicId, from, to)
                .forEach(count -> capacity.put(count.getStatus(), count.getTotal()));
        long free = capacity.getOrDefault(SlotStatus.FREE, 0L);
        long booked = capacity.getOrDefault(SlotStatus.BOOKED, 0L);
        counts.put("OPEN_SLOTS", free);
        counts.put("BOOKED_SLOTS", booked);
        counts.put("CLOSED_SLOTS", capacity.getOrDefault(SlotStatus.CLOSED, 0L));
        counts.put("OCCUPANCY_PERCENT", free + booked == 0 ? 0L : Math.round(100.0 * booked / (free + booked)));
        return counts;
    }

    /** Every practitioner with their specialty and clinics, by last then first name in French order. */
    @Transactional(readOnly = true)
    public List<Practitioner> practitioners() {
        Collator french = Collator.getInstance(Locale.FRENCH);
        return practitioners.findAllBy().stream()
                .sorted(Comparator.comparing(Practitioner::getLastName, french)
                        .thenComparing(Practitioner::getFirstName, french))
                .toList();
    }

    /** One practitioner's clinics and slots starting in {@code [from, to)}; 404 for an id that is no practitioner. */
    @Transactional(readOnly = true)
    public Agenda agenda(UUID practitionerId, Instant from, Instant to) {
        if (!practitioners.existsById(practitionerId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PRACTITIONER_NOT_FOUND", "Ce praticien n'existe pas.");
        }
        return agendas.agenda(practitionerId, from, to);
    }
}
