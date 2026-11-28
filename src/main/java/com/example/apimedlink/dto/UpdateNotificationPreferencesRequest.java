package com.example.apimedlink.dto;

import jakarta.validation.constraints.NotNull;

/** US-15: both preferences, always sent together (a PUT replaces them). Nothing else is read from the body. */
public record UpdateNotificationPreferencesRequest(
        @NotNull(message = "Indiquez si vous souhaitez recevoir les rappels de rendez-vous.")
        Boolean appointmentReminders,

        @NotNull(message = "Indiquez si vous souhaitez être prévenu des nouveaux résultats.")
        Boolean examResults) {}
