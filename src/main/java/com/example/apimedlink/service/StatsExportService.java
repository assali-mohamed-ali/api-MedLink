package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.repository.AppointmentFact;
import com.example.apimedlink.repository.AppointmentStatsRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * MLink-95: an export of anonymised statistics.
 *
 * <p>Anonymity rests on two things. First, only counts are produced, and the patient id read to count people is
 * never written to the file or to the audit trail. Second, a group is released only when it holds at least the
 * minimum number of <em>distinct patients</em> (not appointments: five visits of one patient are one person). A
 * smaller group is left out, and when an indicator has no group left, the whole export is refused rather than
 * delivered empty or partial.
 *
 * <p>Every export is traced (MLink-59) before it is returned: an export is never delivered without its entry, and a
 * refusal is traced too. The entry holds the period and the indicators only, never data.
 */
@Service
public class StatsExportService {

    static final int MAX_DAYS = 366;
    private static final String RESOURCE_TYPE = "STATS";

    private final AppointmentStatsRepository facts;
    private final AuditService audit;
    private final int minGroupSize;

    public StatsExportService(AppointmentStatsRepository facts, AuditService audit,
            @Value("${app.stats.min-group-size:5}") int minGroupSize) {
        if (minGroupSize < 2) {
            throw new IllegalStateException("app.stats.min-group-size must be at least 2: a group of one is a person");
        }
        this.facts = facts;
        this.audit = audit;
        this.minGroupSize = minGroupSize;
    }

    /**
     * The CSV of the chosen indicators for the days {@code from} to {@code to} included (UTC). Each row gives the
     * number of appointments of a group that holds at least the minimum number of distinct patients.
     *
     * @throws ApiException 400 {@code VALIDATION_FAILED} for a bad period or indicators; 422
     *         {@code ANONYMISATION_IMPOSSIBLE} when an indicator has no group with enough distinct patients
     */
    public String export(LocalDate from, LocalDate to, Set<StatsIndicator> requested) {
        validate(from, to, requested);
        EnumSet<StatsIndicator> indicators = EnumSet.copyOf(requested);
        String resource = resourceId(from, to, indicators);

        Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<AppointmentFact> rows = facts.findFacts(start, end);

        StringBuilder csv = new StringBuilder("indicator,group,count\n");
        List<StatsIndicator> withoutGroup = new ArrayList<>();
        for (StatsIndicator indicator : indicators) {
            int kept = 0;
            for (Map.Entry<String, StatsIndicator.Group> group : indicator.count(rows).entrySet()) {
                if (group.getValue().patients() >= minGroupSize) {
                    csv.append(indicator.name()).append(',').append(csvCell(group.getKey())).append(',')
                            .append(group.getValue().appointments()).append('\n');
                    kept++;
                }
            }
            if (kept == 0) {
                withoutGroup.add(indicator);
            }
        }

        if (!withoutGroup.isEmpty()) {
            audit.record(AuditAction.STATS_EXPORTED, RESOURCE_TYPE, resource, AuditOutcome.DENIED);
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ANONYMISATION_IMPOSSIBLE",
                    "Export refusé : sur cette période, aucun groupe ne compte au moins " + minGroupSize
                            + " patients différents pour " + names(withoutGroup)
                            + ". L'anonymisation est impossible.");
        }

        audit.record(AuditAction.STATS_EXPORTED, RESOURCE_TYPE, resource, AuditOutcome.SUCCESS);
        return csv.toString();
    }

    private static void validate(LocalDate from, LocalDate to, Set<StatsIndicator> indicators) {
        Map<String, String> problems = new LinkedHashMap<>();
        if (from == null) {
            problems.put("from", "La date de début est obligatoire.");
        }
        if (to == null) {
            problems.put("to", "La date de fin est obligatoire.");
        }
        if (from != null && to != null) {
            if (from.isAfter(to)) {
                problems.put("to", "La date de fin doit être égale ou postérieure à la date de début.");
            } else if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
                problems.put("to", "La période ne peut pas dépasser " + MAX_DAYS + " jours.");
            }
        }
        if (indicators == null || indicators.isEmpty()) {
            problems.put("indicators", "Choisissez au moins un indicateur.");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Certains paramètres sont invalides.", problems);
        }
    }

    /** Period and indicators, nothing else: the trace never holds data. */
    private static String resourceId(LocalDate from, LocalDate to, Set<StatsIndicator> indicators) {
        return from + ".." + to + ":" + names(indicators);
    }

    private static String names(Collection<StatsIndicator> indicators) {
        return indicators.stream().map(StatsIndicator::name).collect(Collectors.joining(","));
    }

    /**
     * One CSV cell. A value starting with a formula character is prefixed with a quote so a spreadsheet shows it as
     * text; a value with a comma, a quote or a line break is quoted.
     */
    private static String csvCell(String value) {
        String text = value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
