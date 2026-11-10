package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.exception.EmailTakenException;
import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.EmailToken.Purpose;
import com.example.apimedlink.model.Practitioner;
import com.example.apimedlink.model.Specialty;
import com.example.apimedlink.model.User;
import com.example.apimedlink.repository.ClinicRepository;
import com.example.apimedlink.repository.PatientRepository;
import com.example.apimedlink.repository.PractitionerRepository;
import com.example.apimedlink.repository.SessionRevocationStore;
import com.example.apimedlink.repository.SpecialtyRepository;
import com.example.apimedlink.repository.UserRepository;
import com.example.apimedlink.security.Roles;
import jakarta.persistence.EntityNotFoundException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Account management by staff (MLink-69, MLink-70, MLink-71): list, create a practitioner or staff account,
 * change a role, enable or disable an account. A practitioner is a user with a profile (specialty and clinics,
 * V13): it is created with the account, and a role change adds or removes it, so no practitioner account exists
 * without its profile. The Keycloak user and our rows are created in the same request, then the activation mail
 * goes out; when our rows fail, the Keycloak user is deleted, so no account is left half made. Patients are never
 * created here: they register.
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    /** What a practitioner needs besides their account: a specialty and at least one clinic. */
    private record Profile(Specialty specialty, Set<Clinic> clinics) {}

    private final KeycloakAdminService keycloak;
    private final UserRepository users;
    private final PatientRepository patients;
    private final PractitionerRepository practitioners;
    private final SpecialtyRepository specialties;
    private final ClinicRepository clinics;
    private final PractitionerProfiles profiles;
    private final SessionRevocationStore revocations;
    private final EmailTokenService tokens;
    private final MailService mail;
    private final TransactionTemplate tx;

    public AdminUserService(KeycloakAdminService keycloak, UserRepository users, PatientRepository patients,                            PractitionerRepository practitioners, SpecialtyRepository specialties,
                            ClinicRepository clinics, PractitionerProfiles profiles,
                            SessionRevocationStore revocations, EmailTokenService tokens,
                            MailService mail, TransactionTemplate tx) {
        this.keycloak = keycloak;
        this.users = users;
        this.patients = patients;
        this.practitioners = practitioners;
        this.specialties = specialties;
        this.clinics = clinics;
        this.profiles = profiles;
        this.revocations = revocations;
        this.tokens = tokens;
        this.mail = mail;
        this.tx = tx;
    }

    public KeycloakUserPage list(String search, int page, int size) {
        return keycloak.searchUsers(search, page, size);
    }

    /**
     * @throws ApiException 409 {@code EMAIL_TAKEN} when the email already has an account, 400
     *     {@code VALIDATION_FAILED} when a practitioner has no valid specialty or clinics
     */
    public KeycloakUserPage.Entry create(String email, String firstName, String lastName, String role,
                                         UUID specialtyId, Set<UUID> clinicIds) {
        String groupPath = staffGroupOf(role);
        // Everything we can refuse is refused before Keycloak is touched
        Profile profile = Roles.PRACTITIONER.equals(role) ? resolveProfile(specialtyId, clinicIds) : null;
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(normalized) || users.existsByUsername(normalized)) {
            throw emailTaken();
        }
        UUID id;
        try {
            // Outside any transaction: no database connection held while Keycloak answers
            id = keycloak.createUser(normalized, normalized, firstName, lastName, groupPath);
        } catch (EmailTakenException knownToKeycloakOnly) {
            throw emailTaken();
        }
        String token;
        try {
            // The account and its activation token are saved together: both or neither
            token = tx.execute(status -> {
                saveAccount(id, normalized, firstName, lastName, profile);
                return tokens.issue(id, Purpose.ACTIVATE_ACCOUNT);
            });
        } catch (RuntimeException e) {
            deleteKeycloakUser(id);
            throw e;
        }
        sendActivationMail(normalized, firstName, token);
        return new KeycloakUserPage.Entry(
                new KeycloakUser(id, normalized, firstName, lastName, true, false), groupPath);
    }

    /**
     * Changes the role. Everything that can be refused is refused first, then the user's sessions are refused by
     * the API (session_revocations) before anything is changed, so a failure half-way never leaves an admin
     * session valid: the user logs in again, the safe outcome. A promotion adds the practitioner profile, then
     * moves the user to the new Keycloak group. A demotion moves the group first and deletes the profile only
     * after Keycloak succeeded, so a Keycloak failure never leaves a PRACTITIONER without their profile. Keycloak
     * then signs the user out: a session keeps the roles it was opened with. Every step is idempotent: after an
     * error staff send the change again.
     *
     * @throws jakarta.persistence.EntityNotFoundException when the user does not exist (404)
     * @throws ApiException 409 {@code PATIENT_ROLE_CHANGE_UNSUPPORTED} for a patient (a person holds one role; the
     *     history of a patient is not migrated), 409 {@code PRACTITIONER_HAS_SLOTS} when a practitioner who holds
     *     slots would lose their profile, 400 {@code VALIDATION_FAILED} when a new practitioner has no valid
     *     specialty or clinics
     */
    public void changeRole(UUID id, String role, UUID specialtyId, Set<UUID> clinicIds) {
        String groupPath = staffGroupOf(role);
        if (patients.existsById(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "PATIENT_ROLE_CHANGE_UNSUPPORTED",
                    "Le rôle d'un patient ne peut pas être changé : son dossier et ses rendez-vous ne sont pas migrés.");
        }
        boolean hasProfile = practitioners.existsById(id);
        final Runnable profileChange;
        final boolean demotion;
        if (Roles.PRACTITIONER.equals(role) && !hasProfile) {
            Profile profile = resolveProfile(specialtyId, clinicIds);
            if (!users.existsById(id)) {
                throw new EntityNotFoundException("User " + id + " not found");
            }
            KeycloakUser account = keycloak.getUser(id); // 404 when Keycloak does not know it either
            if (isBlank(account.firstName()) || isBlank(account.lastName())) {
                throw new ApiException(HttpStatus.CONFLICT, "PROFILE_NAME_MISSING",
                        "Ce compte n'a ni prénom ni nom : complétez-les avant d'en faire un praticien.");
            }
            Set<UUID> ids = new HashSet<>();
            profile.clinics().forEach(clinic -> ids.add(clinic.getId()));
            profileChange = () -> profiles.create(id, account.firstName(), account.lastName(),
                    profile.specialty().getId(), ids);
            demotion = false;
        } else if (!Roles.PRACTITIONER.equals(role) && hasProfile) {
            if (profiles.hasSlots(id)) {
                throw new ApiException(HttpStatus.CONFLICT, "PRACTITIONER_HAS_SLOTS",
                        "Ce praticien a des créneaux : ils doivent être supprimés avant de changer son rôle.");
            }
            profileChange = () -> profiles.delete(id);
            demotion = true;
        } else {
            profileChange = () -> { };
            demotion = false;
        }
        revocations.revoke(id);
        if (demotion) {
            keycloak.moveToGroup(id, groupPath);
            tx.executeWithoutResult(status -> profileChange.run());
        } else {
            tx.executeWithoutResult(status -> profileChange.run());
            keycloak.moveToGroup(id, groupPath);
        }
        keycloak.endSessions(id);
    }

    /**
     * Enables or disables an account. Disabling signs the user out at once (the API's own mark, then Keycloak).
     *
     * @throws ApiException 409 {@code CANNOT_DISABLE_SELF} when staff disable their own account
     */
    public void setEnabled(UUID id, boolean enabled, UUID actingUser) {
        if (!enabled && id.equals(actingUser)) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_DISABLE_SELF",
                    "Vous ne pouvez pas désactiver votre propre compte.");
        }
        keycloak.setEnabled(id, enabled);
        if (!enabled) {
            revocations.revoke(id);
            keycloak.endSessions(id);
        }
    }

    /** The specialty and clinics a practitioner needs, or a 400 naming what is missing or unknown. */
    private Profile resolveProfile(UUID specialtyId, Set<UUID> clinicIds) {
        Map<String, String> details = new LinkedHashMap<>();
        Specialty specialty = null;
        if (specialtyId == null) {
            details.put("specialtyId", "La spécialité est obligatoire pour un praticien.");
        } else {
            specialty = specialties.findById(specialtyId).orElse(null);
            if (specialty == null) {
                details.put("specialtyId", "Cette spécialité n'existe pas.");
            }
        }
        Set<Clinic> found = new HashSet<>();
        if (clinicIds == null || clinicIds.isEmpty()) {
            details.put("clinicIds", "Au moins une clinique est obligatoire pour un praticien.");
        } else {
            found.addAll(clinics.findAllById(clinicIds));
            if (found.size() != clinicIds.size()) {
                details.put("clinicIds", "Une de ces cliniques n'existe pas.");
            }
        }
        if (!details.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Certains champs sont invalides.", details);
        }
        return new Profile(specialty, found);
    }

    /** A staff member is a plain users row; a practitioner is saved with their profile (both rows at once). */
    private void saveAccount(UUID id, String email, String firstName, String lastName, Profile profile) {
        if (profile == null) {
            users.save(User.builder().id(id).username(email).email(email).build());
            return;
        }
        Practitioner practitioner = new Practitioner(firstName, lastName, profile.specialty());
        practitioner.setId(id);
        practitioner.setUsername(email);
        practitioner.setEmail(email);
        practitioner.setClinics(new HashSet<>(profile.clinics()));
        practitioners.save(practitioner);
    }

    /** The Keycloak group of a staff role; PATIENT and anything unknown are refused. */
    private static String staffGroupOf(String role) {
        String groupPath = RoleGroups.pathOf(role);
        if (groupPath == null || Roles.PATIENT.equals(role)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Le rôle doit être PRACTITIONER ou ADMINISTRATIVE_STAFF.");
        }
        return groupPath;
    }

    /** Staff may learn that an email is taken (unlike a visitor signing up). */
    private static ApiException emailTaken() {
        return new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN",
                "Un compte existe déjà avec cette adresse e-mail.");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** A mail failure is only logged: the account exists, and a new link can be sent later. */
    private void sendActivationMail(String email, String firstName, String token) {
        try {
            mail.sendAccountActivation(email, firstName, token);
        } catch (MailException e) {
            log.warn("Activation mail could not be sent ({})", e.getClass().getSimpleName());
        }
    }

    private void deleteKeycloakUser(UUID id) {
        try {
            keycloak.deleteUser(id);
        } catch (RuntimeException e) {
            log.error("Account creation failed and Keycloak user {} could not be deleted ({}): delete it by hand",
                    id, e.getClass().getSimpleName());
        }
    }
}
