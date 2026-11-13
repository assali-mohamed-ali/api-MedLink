package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditEntry;
import com.example.apimedlink.model.ConsultationReport;
import com.example.apimedlink.repository.AuditEntryRepository;
import com.example.apimedlink.repository.ConsultationReportRepository;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * MLink-32 (32.3): a report that cannot be saved answers REPORT_SAVE_FAILED, a clear error, and leaves nothing behind.
 * The database cannot be made to fail through the API on demand, so the service is called with its repository made
 * to fail; the rest of 32.3 is proven over HTTP by ConsultationReportFunctionalTest.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReportSaveFailureTest {

    @Autowired
    ConsultationReportService service;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    ConsultationReportRepository reports;

    @MockitoSpyBean
    AuditEntryRepository entries;

    @Test
    void aSaveThatFailsAnswersReportSaveFailedAndLeavesNoRow() {
        UUID pro = practitioner();
        UUID patient = patientAttachedTo(pro);
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(reports).saveAndFlush(any(ConsultationReport.class));

        assertThatThrownBy(() -> service.create(pro, patient, "Compte-rendu qui ne sera pas enregistré."))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(error.getCode()).isEqualTo("REPORT_SAVE_FAILED");
                    assertThat(error.getMessage()).contains("n'a pas pu être enregistré").doesNotContain("database");
                });

        assertThat(jdbc.queryForObject("select count(*) from consultation_reports where patient_id = ?", Integer.class, patient))
                .isZero();
    }

    /** 32.4: the report and its REPORT_CREATED row are one unit: if the row cannot be written, no report is kept. */
    @Test
    void aReportWhoseAuditCannotBeWrittenIsNotKept() {
        UUID pro = practitioner();
        UUID patient = patientAttachedTo(pro);
        doThrow(new DataAccessResourceFailureException("audit store unavailable"))
                .when(entries).save(argThat((AuditEntry entry) -> entry != null && entry.getAction() == AuditAction.REPORT_CREATED));

        assertThatThrownBy(() -> service.create(pro, patient, "Compte-rendu sans trace possible."))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("REPORT_SAVE_FAILED"));

        assertThat(jdbc.queryForObject("select count(*) from consultation_reports where patient_id = ?", Integer.class, patient))
                .isZero();
    }

    private UUID practitioner() {
        UUID id = UUID.randomUUID();
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "pro-" + id, "pro-" + id + "@example.com");
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Dev', ?)", id, specialty);
        return id;
    }

    private UUID patientAttachedTo(UUID practitioner) {
        UUID patient = UUID.randomUUID();
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", patient, "patient-" + patient, "patient-" + patient + "@example.com");
        jdbc.update("insert into patients (id, first_name, last_name, birth_date) values (?, 'Amel', 'Ben Salah', ?)",
                patient, Date.valueOf("1990-04-12"));
        UUID clinic = UUID.randomUUID();
        jdbc.update("insert into clinics (id, name, city) values (?, ?, 'Sousse')", clinic, "Clinique " + clinic);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", practitioner, clinic);
        UUID slot = UUID.randomUUID();
        Instant start = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(5, ChronoUnit.DAYS).plus(9, ChronoUnit.HOURS);
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, 'BOOKED')",
                slot, practitioner, clinic, Timestamp.from(start), Timestamp.from(start.plus(30, ChronoUnit.MINUTES)));
        jdbc.update("insert into appointments (id, slot_id, patient_id, booked_by, status) values (?, ?, ?, ?, 'BOOKED')",
                UUID.randomUUID(), slot, patient, patient);
        return patient;
    }
}
