package com.example.apimedlink.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** US-21: a practitioner declares an absence (UTC instants, as the agenda's). The reason is optional. */
public record DeclareAbsenceRequest(
        @NotNull(message = "Le début est obligatoire.") Instant startsAt,
        @NotNull(message = "La fin est obligatoire.") Instant endsAt,
        @Size(max = 200, message = "Le motif est trop long (200 caractères au plus).") String reason) {}
