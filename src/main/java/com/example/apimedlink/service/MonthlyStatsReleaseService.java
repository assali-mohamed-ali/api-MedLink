package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * US-32: fixed non-overlapping monthly windows, one partition per month globally, immutable released
 * results (including refusals). Prevents subtracting overlapping windows or observing updates to a released month.
 * This is suppression and query control, not a formal differential-privacy guarantee.
 */
@Service
public class MonthlyStatsReleaseService {
    private final StatsExportService exports;
    private final AuditService audit;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public MonthlyStatsReleaseService(StatsExportService exports, AuditService audit, JdbcTemplate db,
            TransactionTemplate tx) {
        this.exports = exports;
        this.audit = audit;
        this.db = db;
        this.tx = tx;
    }

    private record Release(String indicator, String csv) {}

    public String export(LocalDate from, LocalDate to, Set<StatsIndicator> requested) {
        if (from == null || to == null || requested == null || requested.size() != 1
                || !from.equals(YearMonth.from(from).atDay(1))
                || !to.equals(YearMonth.from(from).atEndOfMonth())
                || !to.isBefore(LocalDate.now(ZoneOffset.UTC))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Choisissez un mois civil terminé et un seul indicateur.");
        }
        StatsIndicator indicator = requested.iterator().next();
        String resource = from + ".." + to + ":" + indicator.name();
        Release release = tx.execute(status -> {
            // Lock per month before first release. A competing request retries rather than holding connections.
            int month = Math.toIntExact(from.getYear() * 12L + from.getMonthValue());
            if (!Boolean.TRUE.equals(db.queryForObject("select pg_try_advisory_xact_lock(?, ?)",
                    Boolean.class, 32001, month))) {
                throw new ApiException(HttpStatus.CONFLICT, "STATS_RELEASE_BUSY", "Un export est en cours. Réessayez.");
            }
            List<Release> previous = db.query("select indicator, csv from monthly_stats_releases where month_start=?",
                    (row, n) -> new Release(row.getString(1), row.getString(2)), from);
            if (!previous.isEmpty()) {
                Release existing = previous.get(0);
                if (!existing.indicator().equals(indicator.name())) {
                    audit.record(AuditAction.STATS_EXPORTED, "STATS", resource, AuditOutcome.DENIED);
                    throw new ApiException(HttpStatus.CONFLICT, "STATS_PARTITION_LOCKED",
                            "Un autre indicateur a déjà été choisi pour ce mois. Les exports croisés sont interdits.");
                }
                audit.recordWithin(AuditAction.STATS_EXPORTED, "STATS", resource,
                        existing.csv() == null ? AuditOutcome.DENIED : AuditOutcome.SUCCESS);
                return existing;
            }
            String csv;
            try {
                csv = exports.export(from, to, Set.of(indicator));
            } catch (ApiException refused) {
                if (!"ANONYMISATION_IMPOSSIBLE".equals(refused.getCode())) throw refused;
                csv = null;
            }
            db.update("insert into monthly_stats_releases(month_start, indicator, csv) values (?, ?, ?)",
                    from, indicator.name(), csv);
            return new Release(indicator.name(), csv);
        });
        if (release == null || release.csv() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ANONYMISATION_IMPOSSIBLE",
                    "Export indisponible : le seuil de patients distincts n'est pas atteint pour ce mois.");
        }
        return release.csv();
    }
}
