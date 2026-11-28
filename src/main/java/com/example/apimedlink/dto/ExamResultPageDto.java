package com.example.apimedlink.dto;

import java.util.List;

/**
 * US-13 / US-25: one page of examination results, newest first. {@code total} counts them all; {@code unseen} is how
 * many the patient had not seen before this page was read (always 0 in the practitioner's view).
 */
public record ExamResultPageDto(List<ExamResultDto> items, int page, int size, long total, long unseen) {}
