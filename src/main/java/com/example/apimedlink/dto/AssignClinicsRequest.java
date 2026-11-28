package com.example.apimedlink.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** US-29: the full list of clinics a practitioner works in (replaces the current list). At least one. */
public record AssignClinicsRequest(
        @NotEmpty(message = "Au moins une clinique est obligatoire.")
        @Size(max = 20, message = "Trop de cliniques.")
        List<UUID> clinicIds) {}
