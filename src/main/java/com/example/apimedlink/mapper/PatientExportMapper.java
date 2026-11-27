package com.example.apimedlink.mapper;

import com.example.apimedlink.document.DocumentMapper;
import com.example.apimedlink.dto.PatientExportDto;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.service.PatientExport;
import java.time.Instant;

/** US-16: service values to the export's API shape. Called by the controller: services never see a DTO. */
public final class PatientExportMapper {

    private PatientExportMapper() {}

    public static PatientExportDto toDto(PatientExport export, Instant exportedAt) {
        Patient patient = export.patient();
        return new PatientExportDto(
                exportedAt,
                new PatientExportDto.Profile(patient.getFirstName(), patient.getLastName(), patient.getBirthDate(),
                        export.email(), patient.getPhone(), patient.getAddress()),
                export.appointments().stream().map(AppointmentMapper::toDto).toList(),
                export.reports().stream().map(ReportMapper::toDto).toList(),
                export.documents().stream().map(DocumentMapper::toDto).toList());
    }
}
