package com.example.apimedlink.controller;

import com.example.apimedlink.dto.AppointmentDto;
import com.example.apimedlink.dto.StaffBookingRequest;
import com.example.apimedlink.mapper.AppointmentMapper;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.AppointmentService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-30: the administrative staff book an appointment for a patient. {@code /api/admin/**} is kept to staff by
 * SecurityConfig before the body is read. The staff member is recorded as who booked it.
 */
@RestController
@RequestMapping("/api/admin/appointments")
@PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
public class StaffBookingController {

    private final AppointmentService service;
    private final CurrentUser currentUser;

    public StaffBookingController(AppointmentService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Book a free slot for a patient (409 if taken or the patient is busy, 404 unknown patient or slot)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentDto book(@Valid @RequestBody StaffBookingRequest request) {
        return AppointmentMapper.toDto(service.bookFor(request.patientId(), request.slotId(), currentUser.id()));
    }
}
