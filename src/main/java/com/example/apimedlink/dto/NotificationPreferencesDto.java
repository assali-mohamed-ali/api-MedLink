package com.example.apimedlink.dto;

/**
 * US-15: which optional mails the patient receives. {@code appointmentReminders}: the reminder before an appointment
 * (US-12); {@code examResults}: the notice that a new result is in their space (US-25). The booking confirmation and
 * the security mails are always sent. E-mail is the only channel: MedLink sends no SMS.
 */
public record NotificationPreferencesDto(boolean appointmentReminders, boolean examResults) {}
