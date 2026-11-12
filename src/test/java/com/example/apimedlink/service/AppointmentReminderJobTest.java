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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;

/**
 * MLink-111: a mail server outage, which a functional test cannot cause on demand. A failed reminder is tried 3 times,
 * the failure logged with the appointment id only, {@code reminder_sent_at} left empty; once the server is back, the
 * next run sends it.
 */
@ExtendWith(OutputCaptureExtension.class)
class AppointmentReminderJobTest {

    static final Instant NOW = Instant.parse("2026-11-09T08:00:00Z");
    static final String EMAIL = "amel.reminder@example.com";

    final AppointmentRepository appointments = mock(AppointmentRepository.class);
    final PatientRepository patients = mock(PatientRepository.class);
    final MailService mail = mock(MailService.class);
    final AppointmentReminderJob job = new AppointmentReminderJob(appointments, patients, mail,
            Clock.fixed(NOW, ZoneOffset.UTC), 24, Duration.ofMillis(1));

    final Appointment appointment = appointment();

    @BeforeEach
    void oneAppointmentDue() {
        when(appointments.findRemindersDue(eq(AppointmentStatus.BOOKED), any(), any())).thenReturn(List.of(appointment));
        Patient holder = new Patient("Amel", "Ben Salah", null, null, null);
        holder.setId(appointment.getPatientId());
        holder.setEmail(EMAIL);
        when(patients.findAllById(any())).thenReturn(List.of(holder));
        when(appointments.markReminderSent(any(), any())).thenReturn(1);
    }

    @Test
    void whenTheMailServerIsDownTheReminderIsTriedThreeTimesLoggedAndLeftForTheNextRun(CapturedOutput output) {
        doThrow(new MailSendException("connection refused")).when(mail)
                .sendAppointmentReminder(anyString(), anyString(), anyString(), anyString(), any());

        assertThat(job.sendDueReminders()).isZero();

        verify(mail, times(3)).sendAppointmentReminder(eq(EMAIL), eq("Amel"), eq("Clinique Rappel"),
                eq("Dr Rim Jaziri"), eq(appointment.getSlot().getStartsAt()));
        verify(appointments, never()).markReminderSent(any(), any());
        assertThat(output.getOut())
                .contains("Reminder for appointment " + appointment.getId() + " not sent after 3 attempts")
                .doesNotContain(EMAIL)
                .doesNotContain("Clinique Rappel")
                .doesNotContain("Jaziri");
    }

    @Test
    void theServerBackTheNextRunSendsTheReminderAndMarksItSent() {
        doThrow(new MailSendException("connection refused")).when(mail)
                .sendAppointmentReminder(anyString(), anyString(), anyString(), anyString(), any());
        job.sendDueReminders();
        doNothing().when(mail).sendAppointmentReminder(anyString(), anyString(), anyString(), anyString(), any());

        assertThat(job.sendDueReminders()).isEqualTo(1);

        verify(mail, times(4)).sendAppointmentReminder(anyString(), anyString(), anyString(), anyString(), any());
        verify(appointments).markReminderSent(appointment.getId(), NOW);
    }

    @Test
    void aMailThatFailsOnceIsSentAtTheSecondAttemptInTheSameRun() {
        doThrow(new MailSendException("timeout")).doNothing().when(mail)
                .sendAppointmentReminder(anyString(), anyString(), anyString(), anyString(), any());

        assertThat(job.sendDueReminders()).isEqualTo(1);

        verify(mail, times(2)).sendAppointmentReminder(anyString(), anyString(), anyString(), anyString(), any());
        verify(appointments).markReminderSent(appointment.getId(), NOW);
    }

    private static Appointment appointment() {
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
        UUID patientId = UUID.randomUUID();
        Appointment appointment = new Appointment(slot, patientId, patientId);
        appointment.setId(UUID.randomUUID());
        return appointment;
    }
}
