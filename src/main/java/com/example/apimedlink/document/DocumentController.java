package com.example.apimedlink.document;

import com.example.apimedlink.security.Roles;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** MLink-123/125: upload, list and download a patient's documents. */
@RestController
@RequestMapping("/api/patients/{patientId}/documents")
@Validated
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
    public ResponseEntity<DocumentDto> upload(
            @PathVariable UUID patientId,
            @RequestParam("title") String title,
            @RequestParam("file") MultipartFile file) {
        DocumentDto created = DocumentMapper.toDto(service.upload(patientId, title, file));
        return ResponseEntity.created(URI.create("/api/patients/" + patientId + "/documents/" + created.id()))
                .body(created);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('" + Roles.PATIENT + "', '" + Roles.ADMINISTRATIVE_STAFF + "')")
    public DocumentPageDto list(
            @PathVariable UUID patientId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return DocumentMapper.toPage(service.list(patientId, page, size));
    }

    @GetMapping("/{documentId}")
    @PreAuthorize("hasAnyRole('" + Roles.PATIENT + "', '" + Roles.ADMINISTRATIVE_STAFF + "')")
    public ResponseEntity<byte[]> download(@PathVariable UUID patientId, @PathVariable UUID documentId) {
        DownloadedDocument document = service.download(patientId, documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                .contentLength(document.bytes().length)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(document.originalFilename()))
                .body(document.bytes());
    }

    /** RFC 5987: the browser saves the file under its original name, never renders it as a page. */
    private static String attachment(String filename) {
        String fallback = filename.chars().allMatch(character -> character >= 0x20 && character < 0x7f)
                ? filename
                : "document";
        fallback = fallback.replace("\\", "_").replace("\"", "_");
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }
}
