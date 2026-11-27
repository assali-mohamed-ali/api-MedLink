package com.example.apimedlink.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * US-16: a patient's export of their own data. Documents are listed (title, name, size, date); the files are downloaded
 * one by one from their own route.
 */
public record PatientExportDto(
        Instant exportedAt,
        Profile profile,
        List<AppointmentDto> appointments,
        List<MyReportDto> reports,
        List<DocumentDto> documents) {

    public record Profile(String firstName, String lastName, LocalDate birthDate, String email, String phone,
            String address) {}
}
