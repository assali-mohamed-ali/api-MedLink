package com.example.apimedlink.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * US-25: a practitioner publishes an examination result. Checked by the service (presence, lengths, date), after the
 * role check. {@code documentId} is optional: one of the patient's existing documents. {@code requestId} is optional:
 * the id the form generated once; sending it again returns the first result instead of a second one.
 */
public record PublishResultRequest(String title, String examType, LocalDate examDate, String content, UUID documentId,
        UUID requestId) {

    /** Never print a medical text, whoever logs this object. */
    @Override
    public String toString() {
        return "PublishResultRequest[title=***, examType=***, examDate=" + examDate + ", content=***, documentId="
                + documentId + ", requestId=" + requestId + "]";
    }
}
