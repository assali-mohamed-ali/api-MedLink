package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.ExamResultDto;
import com.example.apimedlink.model.ExamResult;
import com.example.apimedlink.service.PatientResult;

/** US-13 / US-25: service values to API shape. Called by controllers: services never see a DTO. */
public final class ExamResultMapper {

    private ExamResultMapper() {}

    public static ExamResultDto toDto(PatientResult result) {
        return new ExamResultDto(result.id(), result.createdAt(), result.title(), result.content(),
                new ExamResultDto.Author(result.practitionerFirstName(), result.practitionerLastName()));
    }

    /** The answer to a publish: what was saved, without repeating the text (the practitioner just wrote it). */
    public static ExamResultDto toPublished(ExamResult result) {
        return new ExamResultDto(result.getId(), result.getCreatedAt(), null, null, null);
    }
}
