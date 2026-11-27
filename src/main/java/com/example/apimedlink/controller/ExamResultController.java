package com.example.apimedlink.controller;

import com.example.apimedlink.dto.ExamResultDto;
import com.example.apimedlink.dto.ExamResultPageDto;
import com.example.apimedlink.dto.PublishResultRequest;
import com.example.apimedlink.mapper.ExamResultMapper;
import com.example.apimedlink.security.CurrentUser;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.ExamResultService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * US-25: a practitioner publishes an examination result for an attached patient; US-13: the patient reads their own.
 * Lists are paged ({@code page} from 0, {@code size} 1 to 50, 20 by default). SecurityConfig checks each role before
 * the request is read; the methods repeat it.
 */
@RestController
public class ExamResultController {

    private final ExamResultService service;
    private final CurrentUser currentUser;

    public ExamResultController(ExamResultService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Publish an examination result for a patient attached to the caller; 200 for a repeated requestId")
    @PostMapping("/api/patients/{id}/results")
    @PreAuthorize("hasRole('" + Roles.PRACTITIONER + "')")
    public ResponseEntity<ExamResultDto> publish(@PathVariable String id, @RequestBody PublishResultRequest request) {
        ExamResultService.Published published = service.publish(currentUser.id(), patientId(id), request.title(),
                request.examType(), request.examDate(), request.content(), request.documentId(), request.requestId());
        return ResponseEntity.status(published.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ExamResultMapper.toPublished(published.result()));
    }

    @Operation(summary = "One page of the results the caller published for a patient attached to them, newest first")
    @GetMapping("/api/patients/{id}/results")
    @PreAuthorize("hasRole('" + Roles.PRACTITIONER + "')")
    public ExamResultPageDto publishedForPatient(@PathVariable String id,
            @RequestParam(name = "page", defaultValue = "0") String pageText,
            @RequestParam(name = "size", defaultValue = "20") String sizeText) {
        UUID patientId = patientId(id);
        PageParams paging = PageParams.read(pageText, sizeText);
        return ExamResultMapper.toPage(service.publishedBy(currentUser.id(), patientId, paging.page(), paging.size()), 0);
    }

    @Operation(summary = "One page of the caller's own examination results, newest first; the page's new ones become seen")
    @GetMapping("/api/results/me")
    @PreAuthorize("hasRole('" + Roles.PATIENT + "')")
    public ExamResultPageDto mine(@RequestParam(name = "page", defaultValue = "0") String pageText,
            @RequestParam(name = "size", defaultValue = "20") String sizeText) {
        PageParams paging = PageParams.read(pageText, sizeText);
        ExamResultService.ResultPage found = service.mine(currentUser.id(), paging.page(), paging.size());
        return ExamResultMapper.toPage(found.results(), found.unseen());
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
