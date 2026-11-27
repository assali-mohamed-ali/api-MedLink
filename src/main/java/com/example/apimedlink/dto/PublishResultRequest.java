package com.example.apimedlink.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * US-25: a practitioner publishes an examination result. Checked by the service (presence, lengths, date), after the
 * role check. {@code documentId} is optional: one of the patient's existing documents.
 */
public record PublishResultRequest(String title, String examType, LocalDate examDate, String content, UUID documentId) {

    /** Never print a medical text, whoever logs this object. */
    @Override
    public String toString() {
        return "PublishResultRequest[title=***, examType=***, examDate=" + examDate + ", content=***, documentId="
                + documentId + "]";
    }
}
