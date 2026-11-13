package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.example.apimedlink.TestcontainersConfiguration;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditEntry;
import com.example.apimedlink.model.SlotStatus;
import com.example.apimedlink.repository.AuditEntryRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * MLink-26, code owner's review on #116: a slot change and its audit row are one unit. If the audit row cannot be
 * written, the change is not kept either (no slot opened, closed or reopened without its trace, and no 500 after a
 * committed change). The audit store's failure cannot be caused through the API on demand, so this test calls the
 * service with the audit repository made to fail; the success path is proven over HTTP by AgendaFunctionalTest.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AgendaAuditAtomicityTest {

    static final UUID SOUSSE = UUID.fromString("c1000000-0000-4000-8000-000000000001");

    /** In 12 days at 10:00 UTC: future, within the opening horizon, away from the other tests' slots. */
    static final Instant START = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(12, ChronoUnit.DAYS).plus(10, ChronoUnit.HOURS);

    @Autowired
    AgendaService agenda;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoSpyBean
    AuditEntryRepository entries;

    @Test
    void anOpeningWhoseAuditCannotBeWrittenLeavesNoSlot() {
        UUID pro = practitioner();
        auditFailsFor(AuditAction.SLOT_OPENED);

        assertThatThrownBy(() -> agenda.open(pro, SOUSSE, START, START.plus(30, ChronoUnit.MINUTES)))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(jdbc.queryForObject("select count(*) from slots where practitioner_id = ?", Integer.class, pro)).isZero();
    }

    @Test
    void aClosingWhoseAuditCannotBeWrittenLeavesTheSlotOpen() {
        UUID pro = practitioner();
        UUID slot = insertSlot(pro, "FREE");
        auditFailsFor(AuditAction.SLOT_CLOSED);

        assertThatThrownBy(() -> agenda.setStatus(pro, slot, SlotStatus.CLOSED))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(status(slot)).isEqualTo("FREE");
    }

    @Test
    void aReopeningWhoseAuditCannotBeWrittenLeavesTheSlotClosed() {
        UUID pro = practitioner();
        UUID slot = insertSlot(pro, "CLOSED");
        auditFailsFor(AuditAction.SLOT_OPENED);

        assertThatThrownBy(() -> agenda.setStatus(pro, slot, SlotStatus.FREE))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(status(slot)).isEqualTo("CLOSED");
    }

    @Test
    void aChangeAndItsAuditRowAreWrittenTogether() {
        UUID pro = practitioner();

        UUID opened = agenda.open(pro, SOUSSE, START, START.plus(30, ChronoUnit.MINUTES)).getId();
        agenda.setStatus(pro, opened, SlotStatus.CLOSED);

        assertThat(jdbc.queryForList("select action from audit_entries where resource_id = ? order by timestamp, id",
                String.class, opened.toString())).containsExactly("SLOT_OPENED", "SLOT_CLOSED");
    }

    @Test
    void nothingChangedMeansNoAuditRow() {
        UUID pro = practitioner();
        UUID slot = insertSlot(pro, "CLOSED");

        agenda.setStatus(pro, slot, SlotStatus.CLOSED);

        assertThat(jdbc.queryForObject("select count(*) from audit_entries where resource_id = ?", Integer.class,
                slot.toString())).isZero();
    }

    private void auditFailsFor(AuditAction action) {
        doThrow(new DataAccessResourceFailureException("audit store unavailable"))
                .when(entries).save(argThat((AuditEntry entry) -> entry != null && entry.getAction() == action));
    }

    private UUID practitioner() {
        UUID id = UUID.randomUUID();
        UUID specialty = UUID.randomUUID();
        jdbc.update("insert into specialties (id, name) values (?, ?)", specialty, "Specialty " + specialty);
        jdbc.update("insert into users (id, username, email) values (?, ?, ?)", id, "pro-" + id, "pro-" + id + "@example.com");
        jdbc.update("insert into practitioners (id, first_name, last_name, specialty_id) values (?, 'Sami', 'Dev', ?)", id, specialty);
        jdbc.update("insert into practitioner_clinics (practitioner_id, clinic_id) values (?, ?)", id, SOUSSE);
        return id;
    }

    private UUID insertSlot(UUID practitioner, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into slots (id, practitioner_id, clinic_id, starts_at, ends_at, status) values (?, ?, ?, ?, ?, ?)",
                id, practitioner, SOUSSE, Timestamp.from(START), Timestamp.from(START.plus(30, ChronoUnit.MINUTES)), status);
        return id;
    }

    private String status(UUID slot) {
        return jdbc.queryForObject("select status from slots where id = ?", String.class, slot);
    }
}
