package com.example.apimedlink.dto;

/**
 * POST /api/appointments/{id}/reschedule (MLink-107): the slot to move to. Text, read after the role check (see
 * {@code AppointmentController}).
 */
public record RescheduleAppointmentRequest(String newSlotId) {}
