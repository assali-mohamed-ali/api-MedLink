package com.example.apimedlink.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** MLink-20: first and last name only; the email never changes here. */
public record UpdateNameRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName) {}
