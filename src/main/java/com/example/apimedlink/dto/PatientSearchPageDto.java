package com.example.apimedlink.dto;

import java.util.List;

/** US-22: one page of the practitioner's patient search; {@code total} counts every match, on every page. */
public record PatientSearchPageDto(List<PatientSummaryDto> items, int page, int size, long total) {}
