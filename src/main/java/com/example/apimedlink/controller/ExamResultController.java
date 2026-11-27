package com.example.apimedlink.controller;

import com.example.apimedlink.dto.ExamResultDto;
import com.example.apimedlink.dto.PublishResultRequest;
import com.example.apimedlink.mapper.ExamResultMapper;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.ExamResultService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-25: a practitioner publishes an examination result for an attached patient; US-13: the patient reads their own.
 * SecurityConfig checks each role before the request is read; the methods repeat it.
 */
@RestController
public class ExamResultController {

    private final ExamResultService service;
    private final CurrentUser currentUser;

    public ExamResultController(ExamResultService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Publish an examination result for a patient attached to the caller")
    @PostMapping("/api/patients/{id}/results")
    @PreAuthorize("hasRole('" + Roles.PRACTITIONER + "')")
    @ResponseStatus(HttpStatus.CREATED)
    public ExamResultDto publish(@PathVariable String id, @RequestBody PublishResultRequest request) {
        return ExamResultMapper.toPublished(service.publish(currentUser.id(), patientId(id), request.title(),
                request.examType(), request.examDate(), request.content(), request.documentId()));
    }

    @Operation(summary = "The results the caller published for a patient attached to them, newest first")
    @GetMapping("/api/patients/{id}/results")
    @PreAuthorize("hasRole('" + Roles.PRACTITIONER + "')")
    public List<ExamResultDto> publishedForPatient(@PathVariable String id) {
        return service.publishedBy(currentUser.id(), patientId(id)).stream().map(ExamResultMapper::toDto).toList();
    }

    @Operation(summary = "The caller's own examination results, newest first")
    @GetMapping("/api/results/me")
    @PreAuthorize("hasRole('" + Roles.PATIENT + "')")
    public List<ExamResultDto> mine() {
        return service.mine(currentUser.id()).stream().map(ExamResultMapper::toDto).toList();
    }

    /** An id that is not a UUID is nobody's attached patient: the same 403 as any other. */
    private static UUID patientId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException notAnId) {
            throw new AccessDeniedException("Not a patient id");
        }
    }
}
