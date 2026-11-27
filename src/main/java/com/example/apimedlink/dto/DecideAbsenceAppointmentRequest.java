package com.example.apimedlink.dto;

import jakarta.validation.constraints.NotNull;

/** US-21: cancel an appointment of an absence, or move it to the same time next week. */
public record DecideAbsenceAppointmentRequest(@NotNull(message = "L'action est obligatoire.") Action action) {

    public enum Action {
        CANCEL,
        POSTPONE
    }
}
