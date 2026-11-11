package com.example.apimedlink.dto;

/**
 * POST /api/appointments (MLink-102): the slot to book, and nothing else. The patient is always the caller, so an
 * id in the body is ignored. {@code slotId} is text, read after the role check (see {@code AppointmentController}).
 */
public record BookAppointmentRequest(String slotId) {}
