package com.example.apimedlink.controller;

import com.example.apimedlink.dto.RecordDto;
import com.example.apimedlink.mapper.RecordMapper;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.PatientRecordService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A patient's record for the practitioner who sees them (MLink-31). Practitioners only: {@code SecurityConfig}
 * checks the role before Spring reads the request, and the class-level rule protects a route added later. The
 * practitioner is always the caller; the id is read as text so nothing is converted before the role check.
 */
@RestController
@RequestMapping("/api/patients")
@PreAuthorize("hasRole('" + Roles.PRACTITIONER + "')")
public class PatientRecordController {

    private final PatientRecordService service;
    private final CurrentUser currentUser;

    public PatientRecordController(PatientRecordService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @Operation(summary = "A patient's record, for a practitioner the patient has an appointment with")
    @GetMapping("/{id}/record")
    public RecordDto record(@PathVariable String id) {
        return RecordMapper.toDto(service.read(currentUser.id(), patientId(id)));
    }

    /** An id that is not a UUID is nobody's attached patient: the same 403 as any other, never a 400. */
    private static UUID patientId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException notAnId) {
            throw new AccessDeniedException("Not a patient id");
        }
    }
}
