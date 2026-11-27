package com.example.apimedlink.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** US-16: staff's decision on a deletion request. A refusal needs a note (the patient reads it). */
public record DecideDeletionRequest(
        @NotNull(message = "La décision est obligatoire.") Decision decision,
        @Size(max = 500, message = "Le motif est trop long (500 caractères au plus).") String note) {

    public enum Decision {
        APPROVED,
        REJECTED
    }
}
