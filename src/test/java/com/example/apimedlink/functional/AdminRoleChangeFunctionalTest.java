package com.example.apimedlink.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.Specialty;
import com.example.apimedlink.repository.ClinicRepository;
import com.example.apimedlink.repository.PractitionerRepository;
import com.example.apimedlink.repository.SessionRevocationStore;
import com.example.apimedlink.repository.SpecialtyRepository;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.AdminUserService;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import com.example.apimedlink.exception.ApiException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MLink-69 (review of PR #112): a role change ends the user's open sessions at once. A session keeps the roles
 * it was opened with, so a demoted admin must not stay admin. Becoming a practitioner also creates their profile.
 * Real Keycloak, real API, real database.
 */
class AdminRoleChangeFunctionalTest extends FunctionalTestBase {

    static final String PASSWORD = "Role-Change-2026!";
    /** A staff-only route; size=1 keeps the Keycloak calls few. */
    static final String STAFF_ONLY = "/api/admin/users?size=1";

    final KeycloakConsole console = new KeycloakConsole();

    @Autowired
    AdminUserService admin;

    @Autowired
    SessionRevocationStore revocations;

    @Autowired
    PractitionerRepository practitioners;

    @Autowired
    SpecialtyRepository specialties;

    @Autowired
    ClinicRepository clinics;

    @Test
    void theLastActiveStaffCannotBeDisabledOrDemoted() throws Exception {
        String email = uniqueEmail("last-staff");
        UUID last = console.createUser(email, PASSWORD, "/administrative-staff");
        new Browser().login(email, PASSWORD);
        UUID specialtyId = specialties.save(new Specialty("Spec-" + last)).getId();
        UUID clinicId = clinics.save(new Clinic("Clinic-" + last, "Sousse")).getId();
        List<UUID> restore = new ArrayList<>();
        try {
            Map<?, ?> group = (Map<?, ?>) console.read("/group-by-path/administrative-staff");
            String groupId = (String) group.get("id");
            for (int first = 0; ; first += 100) {
                List<?> members = (List<?>) console.read("/groups/{id}/members?first={first}&max=100", groupId, first);
                for (Object row : members) {
                    Map<?, ?> member = (Map<?, ?>) row;
                    UUID id = UUID.fromString((String) member.get("id"));
                    if (!id.equals(last) && Boolean.TRUE.equals(member.get("enabled"))) {
                        console.disable(id);
                        restore.add(id);
                    }
                }
                if (members.size() < 100) break;
            }
            assertThatThrownBy(() -> admin.setEnabled(last, false, UUID.randomUUID()))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("LAST_ACTIVE_STAFF"));
            assertThatThrownBy(() -> admin.changeRole(last, Roles.PRACTITIONER, specialtyId, Set.of(clinicId)))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("LAST_ACTIVE_STAFF"));
            assertThat(console.groups(last)).containsExactly("/administrative-staff");
            assertThat(((Map<?, ?>) console.read("/users/{id}", last)).get("enabled")).isEqualTo(true);
            assertThat(practitioners.existsById(last)).isFalse();
        } finally {
            for (UUID id : restore) console.update(id, Map.of("enabled", true));
            console.disable(last);
        }
    }

    @Test
    void aDemotedAdminLosesAdminAccessAtOnceAndLogsInAgainWithTheNewRoleOnly() throws Exception {
        String email = uniqueEmail("demoted");
        UUID id = console.createUser(email, PASSWORD, "/administrative-staff");
        Browser demoted = new Browser().login(email, PASSWORD);
        assertThat(demoted.call(STAFF_ONLY).statusCode()).as("admin before the change").isEqualTo(200);
        UUID specialtyId = specialties.save(new Specialty("Spec-" + id)).getId();
        UUID clinicId = clinics.save(new Clinic("Clinic-" + id, "Sousse")).getId();

        admin.changeRole(id, Roles.PRACTITIONER, specialtyId, Set.of(clinicId));

        assertThat(demoted.call(STAFF_ONLY).statusCode()).as("the old session, at once").isEqualTo(401);
        assertThat(console.groups(id)).containsExactly("/practitioners");
        assertThat(console.sessions(id)).as("Keycloak sessions").isEmpty();
        assertThat(revocations.revokedAt(id)).as("the API's own mark").isPresent();
        assertThat(practitioners.existsById(id)).as("the practitioner profile").isTrue();

        Browser again = new Browser().login(email, PASSWORD);
        assertThat(again.sessionRoles()).as("roles of the new session").containsExactly(Roles.PRACTITIONER);
        assertThat(again.call(STAFF_ONLY).statusCode()).as("no longer admin").isEqualTo(403);
    }
}
