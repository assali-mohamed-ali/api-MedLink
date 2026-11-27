package com.example.apimedlink.dto;

import java.util.UUID;

/** US-22: a patient as a practitioner finds them in a search: id and names only, nothing medical. */
public record PatientSummaryDto(UUID id, String firstName, String lastName) {}
