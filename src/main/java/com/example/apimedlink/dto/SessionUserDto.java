package com.example.apimedlink.dto;

import java.util.List;
import java.util.UUID;

/** The user object of endpoints 5, 6 and 9 (plan.md §4). {@code id} is the Keycloak user id. */
public record SessionUserDto(UUID id, String email, String firstName, String lastName, List<String> roles) {}
