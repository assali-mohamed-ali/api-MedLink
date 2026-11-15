package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.AppointmentStatus;
import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.model.Practitioner;
import com.example.apimedlink.model.Slot;
import com.example.apimedlink.repository.AppointmentRepository;
import com.example.apimedlink.repository.PatientRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

/**
 * MLink-111 (and its review on #120): what a functional test cannot cause on demand. A mail server outage: 3 tries,
 * logged by appointment id only, nothing marked, sent at the next run. A crash after the mail server accepted the
 * reminder but before its marker was committed: the next run sends it again, for the same appointment (same
 * Message-ID): delivery is at least once, and said so. An appointment found cancelled when locked gets no mail.
 */
@ExtendWith(OutputCaptureExtension.class)
class AppointmentReminderJobTest {

    static final Instant NOW = Instant.parse("2026-11-09T08:00:00Z");
    static final String EMAIL = "amel.reminder@example.com";
    static final UUID ID = UUID.randomUUID();
    static final UUID PATIENT = UUID.randomUUID();

    final AppointmentRepository appointments = mock(AppointmentRepository.class);
    final PatientRepository patients = mock(PatientRepository.class);
    final MailService mail = mock(MailService.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final AppointmentReminderJob job = new AppointmentReminderJob(appointments, patients, mail,
            Clock.fixed(NOW, ZoneOffset.UTC), transactions, 24, Duration.ofMillis(1));

    /** Every lock reads the row again, as the database would: a rolled-back marker is not seen by the next read. */
    final List<Appointment> reads = new ArrayList<>();
    AppointmentStatus status = AppointmentStatus.BOOKED;

    @BeforeEach
    void oneAppointmentDue() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(appointments.findRemindersDue(eq(AppointmentStatus.BOOKED), any(), any())).thenReturn(List.of(ID));
        when(appointments.findForChangeById(ID)).thenAnswer(invocation -> {
            Appointment read = appointment(status);
            reads.add(read);
            return Optional.of(read);
        });
        Patient holder = new Patient("Amel", "Ben Salah", null, null, null);
        holder.setId(PATIENT);
        holder.setEmail(EMAIL);
        when(patients.findById(PATIENT)).thenReturn(Optional.of(holder));
    }

    @Test
    void whenTheMailServerIsDownTheReminderIsTriedThreeTimesLoggedAndLeftForTheNextRun(CapturedOutput output) {
        mailFails();

        assertThat(job.sendDueReminders()).isZero();

        verify(mail, times(3)).sendAppointmentReminder(eq(EMAIL), eq("Amel"), eq("Clinique Rappel"),
                eq("Dr Rim Jaziri"), eq(NOW.plus(Duration.ofHours(3))), eq(ID));
        assertThat(reads).allSatisfy(read -> assertThat(read.getReminderSentAt()).isNull());
        verify(transactions, never()).commit(any());
        assertThat(output.getOut())
                .contains("Reminder for appointment " + ID + " not sent after 3 attempts")
                .doesNotContain(EMAIL)
                .doesNotContain("Clinique Rappel")
                .doesNotContain("Jaziri");
    }

    @Test
    void theServerBackTheNextRunSendsTheReminderAndMarksItSent() {
        mailFails();
        job.sendDueReminders();
        doNothing().when(mail).sendAppointmentReminder(anyString(), anyString(), anyString(), anyString(), any(), any());

        assertThat(job.sendDueReminders()).isEqualTo(1);

        verify(mail, times(4)).sendAppointmentReminder(anyString(), anyString(), anyString(), anyString(), any(), eq(ID));
        assertThat(reads.get(reads.size() - 1).getReminderSentAt()).isEqualTo(NOW);
        verify(transactions).commit(any());
    }

    @Test
    void aMailThatFailsOnceIsSentAtTheSecondAttemptInTheSameRun() {
        doThrow(new MailSendException("timeout")).doNothing().when(mail)
                .sendAppointmentReminder(anyString(), anyString(), anyString(), anyString(), any(), any());

        assertThat(job.sendDueReminders()).isEqualTo(1);

        verify(mail, times(2)).sendAppointmentReminder(anyString(), anyString(), anyString(), anyString(), any(), eq(ID));
    }

    /** At least once: the mail went out, its marker did not commit, so the next run sends the same reminder again. */
    @Test
    void aReminderSentButNotMarkedIsSentAgainAtTheNextRunAsTheSameMessage(CapturedOutput output) {
        doThrow(new TransactionSystemException("database gone")).doNothing().when(transactions).commit(any());

        assertThat(job.sendDueReminders()).isZero();
        assertThat(output.getOut()).contains("Reminder for appointment " + ID + " was sent but not marked");
        assertThat(job.sendDueReminders()).isEqualTo(1);

        verify(mail, times(2)).sendAppointmentReminder(eq(EMAIL), anyString(), anyString(), anyString(), any(), eq(ID));
        assertThat(MailService.reminderMessageId(ID)).isEqualTo("<appointment-reminder-" + ID + "@medlink.local>");
    }

    @Test
    void anAppointmentFoundCancelledWhenLockedGetsNoMail() {
        status = AppointmentStatus.CANCELLED;

        assertThat(job.sendDueReminders()).isZero();

        verify(mail, never()).sendAppointmentReminder(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    private void mailFails() {
        doThrow(new MailSendException("connection refused")).when(mail)
                .sendAppointmentReminder(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    private static Appointment appointment(AppointmentStatus status) {
        Practitioner practitioner = new Practitioner();
        practitioner.setFirstName("Rim");
        practitioner.setLastName("Jaziri");
        Clinic clinic = new Clinic();
        clinic.setName("Clinique Rappel");
        Slot slot = new Slot();
        slot.setPractitioner(practitioner);
        slot.setClinic(clinic);
        slot.setStartsAt(NOW.plus(Duration.ofHours(3)));
        slot.setEndsAt(NOW.plus(Duration.ofHours(3)).plus(Duration.ofMinutes(30)));
        Appointment appointment = new Appointment(slot, PATIENT, PATIENT);
        appointment.setId(ID);
        appointment.setStatus(status);
        return appointment;
    }
}
