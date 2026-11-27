package com.example.apimedlink.dto;

import java.util.UUID;

/**
 * US-22: a patient as a practitioner finds them in a search: id, names and record number ({@code MED-000042}), nothing
 * medical. Every patient listed is one the practitioner may open (the record applies the same link rule).
 */
public record PatientSummaryDto(UUID id, String firstName, String lastName, String recordNumber) {}
