package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.ExamResultDto;
import com.example.apimedlink.dto.ExamResultPageDto;
import com.example.apimedlink.model.ExamResult;
import com.example.apimedlink.service.PatientResult;
import org.springframework.data.domain.Page;

/** US-13 / US-25: service values to API shape. Called by controllers: services never see a DTO. */
public final class ExamResultMapper {

    private ExamResultMapper() {}

    public static ExamResultDto toDto(PatientResult result) {
        ExamResultDto.Author author = result.practitionerFirstName() == null && result.practitionerLastName() == null
                ? null : new ExamResultDto.Author(result.practitionerFirstName(), result.practitionerLastName());
        return new ExamResultDto(result.id(), result.publishedAt(), result.examDate(), result.title(), result.examType(),
                result.content(), result.documentId(), result.isNew(), author);
    }

    public static ExamResultPageDto toPage(Page<PatientResult> page, long unseen) {
        return new ExamResultPageDto(page.getContent().stream().map(ExamResultMapper::toDto).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), unseen);
    }

    /** The answer to a publish: what was saved, without repeating the texts (the practitioner just wrote them). */
    public static ExamResultDto toPublished(ExamResult result) {
        return new ExamResultDto(result.getId(), result.getCreatedAt(), result.getExamDate(), null, null, null,
                result.getDocumentId(), false, null);
    }
}
