package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.PatientSearchPageDto;
import com.example.apimedlink.dto.PatientSummaryDto;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.model.RecordNumbers;
import org.springframework.data.domain.Page;

/** US-22: entity to API shape for the practitioner's patient search. */
public final class PatientSummaryMapper {

    private PatientSummaryMapper() {}

    public static PatientSummaryDto toDto(Patient patient) {
        return new PatientSummaryDto(patient.getId(), patient.getFirstName(), patient.getLastName(),
                RecordNumbers.label(patient.getRecordNumber()));
    }

    public static PatientSearchPageDto toPage(Page<Patient> page) {
        return new PatientSearchPageDto(page.getContent().stream().map(PatientSummaryMapper::toDto).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
