package com.example.apimedlink.document;

import org.springframework.data.domain.Page;

/** Entity to API shape. Called by the controller: the service never sees a DTO. */
public final class DocumentMapper {

    private DocumentMapper() {}

    public static DocumentDto toDto(PatientDocument document) {
        return new DocumentDto(
                document.getId(),
                document.getPatientId(),
                document.getUploadedBy(),
                document.getTitle(),
                document.getOriginalFilename(),
                document.getContentType(),
                document.getSizeBytes(),
                document.getCreatedAt());
    }

    public static DocumentPageDto toPage(Page<PatientDocument> page) {
        return new DocumentPageDto(
                page.getContent().stream().map(DocumentMapper::toDto).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements());
    }
}
