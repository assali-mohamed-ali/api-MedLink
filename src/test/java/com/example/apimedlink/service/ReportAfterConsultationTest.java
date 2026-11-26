package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.ApiException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MLink-32 follow-up: (1) a practitioner writes a report only once a consultation with the patient has started (a
 * BOOKED appointment whose slot has begun), never before; the record says whether it is possible. (2) The patient reads
 * their own reports, newest first, with the practitioner who wrote each one, and nobody else's. The access rules over
 * HTTP are in ConsultationReportFunctionalTest and MyReportsFunctionalTest.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReportAfterConsultationTest {

    static final Instant PAST = Instant.now().minus(2, ChronoUnit.DAYS);
    static final Instant FUTURE = Instant.now().plus(3, ChronoUnit.DAYS);

    @Autowired
    ConsultationReportService reports;

    @Autowired
    PatientRecordService records;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void beforeTheConsultationStartsAReportIsRefusedAndNothingIsSaved() {
        UUID pro = practitioner("Sami", "Dev");
        UUID patient = patient();
        appointment(pro, patient, FUTURE, "BOOKED");

        assertThatThrownBy(() -> reports.create(pro, patient, "Écrit trop tôt."))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(refused.getCode()).isEqualTo("CONSULTATION_NOT_STARTED");
                    assertThat(refused.getMessage())
                            .isEqualTo("Vous pourrez écrire le compte-rendu une fois la consultation commencée.");
                });

        assertThat(count("select count(*) from consultation_reports where patient_id = ?", patient)).isZero();
        assertThat(count("select count(*) from audit_entries where action = 'REPORT_CREATED' and user_id = ?", pro)).isZero();
    }

    @Test
    void onceTheConsultationHasStartedTheReportIsSaved() {
        UUID pro = practitioner("Sami", "Dev");
        UUID patient = patient();
        appointment(pro, patient, Instant.now().minus(5, ChronoUnit.MINUTES), "BOOKED");

        assertThat(reports.create(pro, patient, "Consultation en cours.").content()).isEqualTo("Consultation en cours.");
    }

    /** A past appointment that was cancelled is no consultation: with only an upcoming one, still too early. */
    @Test
    void aCancelledPastAppointmentDoesNotOpenTheReport() {
        UUID pro = practitioner("Sami", "Dev");
        UUID patient = patient();
        appointment(pro, patient, PAST, "CANCELLED");
        appointment(pro, patient, FUTURE, "BOOKED");

        assertThatThrownBy(() -> reports.create(pro, patient, "Écrit trop tôt."))
                .isInstanceOfSatisfying(ApiException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("CONSULTATION_NOT_STARTED"));
    }

    @Test
    void theRecordSaysWhetherAReportCanBeWritten() {
        UUID pro = practitioner("Sami", "Dev");
        UUID upcomingOnly = patient();
        appointment(pro, upcomingOnly, FUTURE, "BOOKED");
        UUID seen = patient();
        appointment(pro, seen, PAST, "BOOKED");

        assertThat(records.read(pro, upcomingOnly).canWriteReport()).isFalse();
        assertThat(records.read(pro, seen).canWriteReport()).isTrue();
    }

    @Test
    void aPatientReadsTheirOwnReportsNewestFirstWithWhoWroteThemAndNobodyElses() {
        UUID first = practitioner("Leila", "Ben Ali");
        UUID second = practitioner("Nadia", "Haddad");
        UUID patient = patient();
        UUID other = patient();
        appointment(first, patient, PAST, "BOOKED");
        appointment(second, patient, PAST.plus(1, ChronoUnit.HOURS), "BOOKED");
        appointment(first, other, PAST.plus(2, ChronoUnit.HOURS), "BOOKED");
        reports.create(first, patient, "Premier compte-rendu.");
        reports.create(second, patient, "Deuxième compte-rendu.");
        reports.create(first, other, "Compte-rendu d'un autre patient.");

        var mine = reports.mine(patient);

        assertThat(mine).extracting(PatientReport::content).containsExactly("Deuxième compte-rendu.", "Premier compte-rendu.");
        assertThat(mine).extracting(PatientReport::practitionerFirstName, PatientReport::practitionerLastName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Nadia", "Haddad"),
                        org.assertj.core.groups.Tuple.tuple("Leila", "Ben Ali"));
        assertThat(mine).allSatisfy(report -> assertThat(report.specialty()).startsWith("Specialty "));
        assertThat(reports.mine(UUID.randomUUID())).isEmpty();
    }

    /** 32.4: the patient's read is traced like a practitioner's, ids only. */
    @Test
    void aPatientsReadIsTraced() {
        UUID patient = patient();

        reports.mine(patient);

        assertThat(count("select count(*) from audit_entries where action = 'REPORT_VIEWED' and resource_type = "
                + "'PATIENT_REPORTS' and resource_id = ?", patient.toString())).isEqualTo(1);
    }

    private int count(String sql, Object id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private UUID practitioner(String firstName, String lastName) {
        UUID id = UUID.randomUUID();
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "pro-" + id, "pro-" + id + "@example.com");
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, ?, ?, ?)",
                id, firstName, lastName, specialty);
        return id;
    }

    private UUID patient() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "patient-" + id, "patient-" + id + "@example.com");
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                id, Date.valueOf("1990-04-12"));
        return id;
    }

    /** One clinic of the practitioner's, one slot starting at {@code start}, one appointment of the patient on it. */
    private void appointment(UUID practitioner, UUID patient, Instant start, String status) {
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, 'Sousse')", clinic, "Clinique " + clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?) on conflict do nothing",
                practitioner, clinic);
        UUID slot = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, ?)",
                slot, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)),
                "BOOKED".equals(status) ? "BOOKED" : "FREE");
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, ?)",
                UUID.randomUUID(), slot, patient, patient, status);
    }
}
