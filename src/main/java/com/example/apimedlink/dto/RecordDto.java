package com.example.apimedlink.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A patient's record as a practitioner sees it (MLink-31): the identity the consultation needs, never the address
 * or the email. {@code reports} stays empty until the consultation reports exist (MLink-32 gives it its type).
 * {@code canWriteReport}: a consultation has started, so the practitioner may write a report (MLink-32 follow-up).
 */
public record RecordDto(PatientIdentity patient, List<Object> reports, boolean canWriteReport) {

    public record PatientIdentity(UUID id, String firstName, String lastName, LocalDate birthDate, String phone) {}
}
