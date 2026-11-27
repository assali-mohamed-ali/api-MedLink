package com.example.apimedlink.controller;

import com.example.apimedlink.dto.AdminPractitionerDto;
import com.example.apimedlink.dto.AssignClinicsRequest;
import com.example.apimedlink.mapper.ClinicActivityMapper;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.PractitionerClinicService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-29: staff set the clinics of a practitioner. {@code /api/admin/**} is kept to staff by SecurityConfig before any
 * body is read.
 */
@RestController
@RequestMapping("/api/admin/practitioners")
@PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
public class PractitionerClinicController {

    private final PractitionerClinicService service;

    public PractitionerClinicController(PractitionerClinicService service) {
        this.service = service;
    }

    @Operation(summary = "Replace the clinics a practitioner works in (409 if a removed clinic still holds their slots)")
    @PutMapping("/{practitionerId}/clinics")
    public AdminPractitionerDto assign(@PathVariable UUID practitionerId,
            @Valid @RequestBody AssignClinicsRequest request) {
        return ClinicActivityMapper.toDtos(List.of(service.assign(practitionerId, request.clinicIds()))).get(0);
    }
}
