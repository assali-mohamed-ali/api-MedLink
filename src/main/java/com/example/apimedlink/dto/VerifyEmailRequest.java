package com.example.apimedlink.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyEmailRequest(@NotBlank(message = "Le lien est incomplet.") @Size(max = 100) String token) {}
