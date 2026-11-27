package com.example.apimedlink.dto;

import com.example.apimedlink.document.DocumentDto;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * US-16: a patient's export of their own data (format {@value #FORMAT}). Documents are listed (title, name, size,
 * date); the files, a result's attachment included ({@code results[].documentId} is one of {@code documents[].id}),
 * are downloaded one by one from {@value #DOCUMENT_ROUTE}, behind the patient's login. {@code consent} is null when
 * the account was created before consent was recorded; {@code deletionRequest} is the state of the patient's request.
 */
public record PatientExportDto(
        String format,
        Instant exportedAt,
        Profile profile,
        Consent consent,
        NotificationPreferencesDto notificationPreferences,
        List<AppointmentDto> appointments,
        List<MyReportDto> reports,
        List<ExamResultDto> results,
        List<DocumentDto> documents,
        String documentDownloadRoute,
        DeletionRequestDto deletionRequest) {

    public static final String FORMAT = "medlink-export/2";
    public static final String DOCUMENT_ROUTE = "/api/patients/{patientId}/documents/{documentId}";

    public record Profile(String firstName, String lastName, LocalDate birthDate, String email, String phone,
            String address) {}

    public record Consent(String version, Instant acceptedAt) {}
}
