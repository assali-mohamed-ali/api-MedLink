package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.RecordDto;
import com.example.apimedlink.model.Patient;
import com.example.apimedlink.service.PatientRecord;
import java.util.List;

/** Entity to API shape. Called by controllers: services never see a DTO. */
public final class RecordMapper {

    private RecordMapper() {}

    public static RecordDto toDto(PatientRecord record) {
        Patient patient = record.patient();
        return new RecordDto(
                new RecordDto.PatientIdentity(patient.getId(), patient.getFirstName(), patient.getLastName(),
                        patient.getBirthDate(), patient.getPhone()),
                List.of());
    }
}
