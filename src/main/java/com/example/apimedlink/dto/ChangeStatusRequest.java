package com.example.apimedlink.dto;

import jakarta.validation.constraints.NotNull;

/** Body of PATCH /api/admin/users/{id}/status. */
public record ChangeStatusRequest(
        @NotNull(message = "L'état du compte est obligatoire.")
        Boolean enabled) {}
