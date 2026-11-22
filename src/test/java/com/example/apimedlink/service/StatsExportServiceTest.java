package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AppointmentStatus;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.repository.AppointmentFact;
import com.example.apimedlink.repository.AppointmentStatsRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MLink-95: anonymisation (distinct patients), refusal, trace and validation of the statistics export. */
class StatsExportServiceTest {

    private static final LocalDate FROM = LocalDate.parse("2026-11-01");
    private static final LocalDate TO = LocalDate.parse("2026-11-30");

    private final AppointmentStatsRepository facts = mock(AppointmentStatsRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final StatsExportService service = new StatsExportService(facts, audit, 5);

    private static AppointmentFact fact(String day, AppointmentStatus status, String specialty, UUID patient) {
        return new AppointmentFact(Instant.parse(day + "T10:00:00Z"), status, specialty, patient);
    }

    /** One appointment of a new patient. */
    private static AppointmentFact fact(String day, AppointmentStatus status, String specialty) {
        return fact(day, status, specialty, UUID.randomUUID());
    }

    /** {@code count} appointments, each of a different patient. */
    private static List<AppointmentFact> many(int count, String day, AppointmentStatus status, String specialty) {
        List<AppointmentFact> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(fact(day, status, specialty));
        }
        return rows;
    }

    /** {@code count} appointments, all of the same patient. */
    private static List<AppointmentFact> manyOfOnePatient(
            int count, String day, AppointmentStatus status, String specialty, UUID patient) {
        List<AppointmentFact> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(fact(day, status, specialty, patient));
        }
        return rows;
    }

    private void databaseHolds(List<AppointmentFact> rows) {
        when(facts.findFacts(any(), any())).thenReturn(rows);
    }

    @Test
    void aGroupSmallerThanTheMinimumIsLeftOut() {
        List<AppointmentFact> rows = many(6, "2026-11-10", AppointmentStatus.BOOKED, "Cardiologie");
        rows.addAll(many(2, "2026-11-10", AppointmentStatus.CANCELLED, "Cardiologie"));
        databaseHolds(rows);

        String csv = service.export(FROM, TO, EnumSet.of(StatsIndicator.APPOINTMENTS_PER_STATUS));

        assertThat(csv).isEqualTo("indicator,group,count\nAPPOINTMENTS_PER_STATUS,BOOKED,6\n");
        assertThat(csv).doesNotContain("CANCELLED");
    }

    @Test
    void fiveAppointmentsOfOnePatientAreNotFivePeople() {
        UUID patient = UUID.randomUUID();
        databaseHolds(manyOfOnePatient(5, "2026-11-10", AppointmentStatus.BOOKED, "Cardiologie", patient));

        assertThatThrownBy(() -> service.export(FROM, TO, EnumSet.of(StatsIndicator.APPOINTMENTS_PER_SPECIALTY)))
                .isInstanceOfSatisfying(ApiException.class,
                        refusal -> assertThat(refusal.getCode()).isEqualTo("ANONYMISATION_IMPOSSIBLE"));

        verify(audit).record(eq(AuditAction.STATS_EXPORTED), eq("STATS"), anyString(), eq(AuditOutcome.DENIED));
    }

    @Test
    void fiveVisitsOfOnePatientOnOneDayAreNotReleasedPerDay() {
        UUID patient = UUID.randomUUID();
        databaseHolds(manyOfOnePatient(5, "2026-11-10", AppointmentStatus.BOOKED, "Cardiologie", patient));

        assertThatThrownBy(() -> service.export(FROM, TO, EnumSet.of(StatsIndicator.APPOINTMENTS_PER_DAY)))
                .isInstanceOfSatisfying(ApiException.class,
                        refusal -> assertThat(refusal.getCode()).isEqualTo("ANONYMISATION_IMPOSSIBLE"));
    }

    @Test
    void fourPatientsWithManyAppointmentsEachAreStillTooFew() {
        List<AppointmentFact> rows = new ArrayList<>();
        for (int patient = 0; patient < 4; patient++) {
            rows.addAll(manyOfOnePatient(
                    3, "2026-11-10", AppointmentStatus.BOOKED, "Cardiologie", UUID.randomUUID()));
        }
        databaseHolds(rows);

        assertThatThrownBy(() -> service.export(FROM, TO, EnumSet.of(StatsIndicator.APPOINTMENTS_PER_STATUS)))
                .isInstanceOfSatisfying(ApiException.class,
                        refusal -> assertThat(refusal.getCode()).isEqualTo("ANONYMISATION_IMPOSSIBLE"));
    }

    @Test
    void fivePatientsWithSeveralAppointmentsEachAreReleasedWithTheAppointmentCount() {
        List<AppointmentFact> rows = new ArrayList<>();
        for (int patient = 0; patient < 5; patient++) {
            rows.addAll(manyOfOnePatient(
                    2, "2026-11-10", AppointmentStatus.BOOKED, "Cardiologie", UUID.randomUUID()));
        }
        databaseHolds(rows);

        String csv = service.export(FROM, TO, EnumSet.of(StatsIndicator.APPOINTMENTS_PER_STATUS));

        assertThat(csv).isEqualTo("indicator,group,count\nAPPOINTMENTS_PER_STATUS,BOOKED,10\n");
    }

    @Test
    void thePatientIdNeverReachesTheExportOrTheTrace() {
        List<AppointmentFact> rows = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int patient = 0; patient < 5; patient++) {
            UUID id = UUID.randomUUID();
            ids.add(id.toString());
            rows.add(fact("2026-11-10", AppointmentStatus.BOOKED, "Cardiologie", id));
        }
        databaseHolds(rows);

        String csv = service.export(FROM, TO, EnumSet.of(StatsIndicator.APPOINTMENTS_PER_STATUS));

        for (String id : ids) {
            assertThat(csv).doesNotContain(id);
        }
        verify(audit).record(AuditAction.STATS_EXPORTED, "STATS",
                "2026-11-01..2026-11-30:APPOINTMENTS_PER_STATUS", AuditOutcome.SUCCESS);
    }

    @Test
    void theExportHoldsOnlyAnIndicatorAGroupAndACount() {
        List<AppointmentFact> rows = many(5, "2026-11-10", AppointmentStatus.BOOKED, "Cardiologie");
        rows.addAll(many(7, "2026-11-11", AppointmentStatus.CANCELLED, "Dermatologie"));
        databaseHolds(rows);

        String csv = service.export(FROM, TO, EnumSet.allOf(StatsIndicator.class));

        String[] lines = csv.split("\n");
        assertThat(lines[0]).isEqualTo("indicator,group,count");
        assertThat(lines).hasSizeGreaterThan(1);
        for (int i = 1; i < lines.length; i++) {
            assertThat(lines[i]).matches("APPOINTMENTS_PER_[A-Z]+,[^,]+,\\d+");
        }
        assertThat(csv).contains("APPOINTMENTS_PER_DAY,2026-11-10,5")
                .contains("APPOINTMENTS_PER_DAY,2026-11-11,7")
                .contains("APPOINTMENTS_PER_SPECIALTY,Cardiologie,5")
                .contains("APPOINTMENTS_PER_SPECIALTY,Dermatologie,7");
    }

    @Test
    void theExportIsRefusedWhenNoGroupIsBigEnough() {
        databaseHolds(many(3, "2026-11-10", AppointmentStatus.BOOKED, "Cardiologie"));

        assertThatThrownBy(() -> service.export(FROM, TO, EnumSet.allOf(StatsIndicator.class)))
                .isInstanceOfSatisfying(ApiException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo("ANONYMISATION_IMPOSSIBLE");
                    assertThat(refusal.getStatus().value()).isEqualTo(422);
                });

        verify(audit).record(eq(AuditAction.STATS_EXPORTED), eq("STATS"), anyString(), eq(AuditOutcome.DENIED));
        verify(audit, never()).record(any(), anyString(), anyString(), eq(AuditOutcome.SUCCESS));
    }

    @Test
    void theExportIsRefusedWhenOneChosenIndicatorHasNoBigGroup() {
        List<AppointmentFact> rows = new ArrayList<>();
        for (int day = 1; day <= 6; day++) {
            rows.add(fact("2026-11-0" + day, AppointmentStatus.BOOKED, "Cardiologie"));
        }
        databaseHolds(rows);

        // Per status there are 6 different patients, enough; per day each day holds 1: nothing is delivered.
        assertThatThrownBy(() -> service.export(FROM, TO,
                EnumSet.of(StatsIndicator.APPOINTMENTS_PER_STATUS, StatsIndicator.APPOINTMENTS_PER_DAY)))
                .isInstanceOfSatisfying(ApiException.class,
                        refusal -> assertThat(refusal.getCode()).isEqualTo("ANONYMISATION_IMPOSSIBLE"));
    }

    @Test
    void aSuccessfulExportIsTracedWithThePeriodAndTheIndicatorsOnly() {
        databaseHolds(many(6, "2026-11-10", AppointmentStatus.BOOKED, "Cardiologie"));

        service.export(FROM, TO, EnumSet.of(StatsIndicator.APPOINTMENTS_PER_STATUS));

        verify(audit).record(AuditAction.STATS_EXPORTED, "STATS",
                "2026-11-01..2026-11-30:APPOINTMENTS_PER_STATUS", AuditOutcome.SUCCESS);
    }

    @Test
    void noExportIsReturnedWhenItsTraceCannotBeWritten() {
        databaseHolds(many(6, "2026-11-10", AppointmentStatus.BOOKED, "Cardiologie"));
        doThrow(new IllegalStateException("audit down")).when(audit)
                .record(any(), anyString(), anyString(), eq(AuditOutcome.SUCCESS));

        assertThatThrownBy(() -> service.export(FROM, TO, EnumSet.of(StatsIndicator.APPOINTMENTS_PER_STATUS)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSpecialtyThatLooksLikeAFormulaIsExportedAsText() {
        databaseHolds(many(6, "2026-11-10", AppointmentStatus.BOOKED, "=1+1"));

        String csv = service.export(FROM, TO, EnumSet.of(StatsIndicator.APPOINTMENTS_PER_SPECIALTY));

        assertThat(csv).contains("APPOINTMENTS_PER_SPECIALTY,'=1+1,6");
    }

    @Test
    void aSpecialtyWithACommaIsQuoted() {
        databaseHolds(many(6, "2026-11-10", AppointmentStatus.BOOKED, "ORL, voix"));

        String csv = service.export(FROM, TO, EnumSet.of(StatsIndicator.APPOINTMENTS_PER_SPECIALTY));

        assertThat(csv).contains("APPOINTMENTS_PER_SPECIALTY,\"ORL, voix\",6");
    }

    @Test
    void badPeriodsAndIndicatorsAreRejectedBeforeAnyReadOrTrace() {
        EnumSet<StatsIndicator> all = EnumSet.allOf(StatsIndicator.class);

        assertInvalid(() -> service.export(TO, FROM, all));
        assertInvalid(() -> service.export(null, TO, all));
        assertInvalid(() -> service.export(FROM, null, all));
        assertInvalid(() -> service.export(LocalDate.parse("2025-01-01"), TO, all));
        assertInvalid(() -> service.export(FROM, TO, EnumSet.noneOf(StatsIndicator.class)));

        verifyNoInteractions(facts, audit);
    }

    @Test
    void aMinimumGroupSizeBelowTwoIsRefusedAtStartup() {
        assertThatThrownBy(() -> new StatsExportService(facts, audit, 1)).isInstanceOf(IllegalStateException.class);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getCode()).isEqualTo("VALIDATION_FAILED");
            assertThat(error.getStatus().value()).isEqualTo(400);
        });
    }
}
