package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.ReportDto;
import com.example.apimedlink.service.ReadableReport;

/** Service values to API shape. Called by controllers: services never see a DTO. */
public final class ReportMapper {

    private ReportMapper() {}

    public static ReportDto toDto(ReadableReport report) {
        return new ReportDto(report.id(), report.createdAt(), report.content());
    }
}
