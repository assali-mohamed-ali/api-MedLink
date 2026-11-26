package com.example.apimedlink.controller;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.security.Roles;
import com.example.apimedlink.service.StatsExportService;
import com.example.apimedlink.service.StatsIndicator;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** MLink-95: administrative export of anonymised statistics, as a CSV file. */
@RestController
@RequestMapping("/api/admin/stats")
public class StatsExportController {

    private final StatsExportService exports;

    public StatsExportController(StatsExportService exports) {
        this.exports = exports;
    }

    /**
     * {@code from} and {@code to} are days ({@code yyyy-MM-dd}, both included); {@code indicators} is a comma-separated
     * list of {@link StatsIndicator} names, all of them when absent.
     */
    @GetMapping("/export")
    @PreAuthorize("hasRole('" + Roles.ADMINISTRATIVE_STAFF + "')")
    public ResponseEntity<String> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String indicators) {
        String csv = exports.export(from, to, parseIndicators(indicators));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("stats-" + from + "_" + to + ".csv").build()
                                .toString())
                .body(csv);
    }

    private static Set<StatsIndicator> parseIndicators(String indicators) {
        if (indicators == null || indicators.isBlank()) {
            return EnumSet.allOf(StatsIndicator.class);
        }
        EnumSet<StatsIndicator> chosen = EnumSet.noneOf(StatsIndicator.class);
        for (String name : indicators.split(",")) {
            try {
                chosen.add(StatsIndicator.valueOf(name.trim()));
            } catch (IllegalArgumentException unknown) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                        "Certains paramètres sont invalides.",
                        Map.of("indicators", "Un indicateur demandé n'existe pas."));
            }
        }
        return chosen;
    }
}
