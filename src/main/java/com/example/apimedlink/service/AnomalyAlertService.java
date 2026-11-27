package com.example.apimedlink.service;

import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.repository.AuditEntryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AnomalyAlertService {

    private static final Logger LOG = LoggerFactory.getLogger(AnomalyAlertService.class);

    private record Source(AuditAction action, UUID userId, String ipAddress) {}

    private final AuditEntryRepository entries;
    private final MailService mail;
    private final Clock clock;
    private final String recipient;
    private final int threshold;
    private final Duration window;
    private final Duration cooldown;
    private final Map<Source, Instant> nextAlert = new HashMap<>();

    public AnomalyAlertService(AuditEntryRepository entries, MailService mail, Clock clock,
            @Value("${app.security-alerts.recipient:}") String recipient,
            @Value("${app.security-alerts.threshold:5}") int threshold,
            @Value("${app.security-alerts.window:PT10M}") Duration window,
            @Value("${app.security-alerts.cooldown:PT30M}") Duration cooldown) {
        if (threshold < 1 || window.isZero() || window.isNegative()
                || cooldown.isZero() || cooldown.isNegative()) {
            throw new IllegalArgumentException("Security alert threshold, window and cooldown must be positive");
        }
        this.entries = entries;
        this.mail = mail;
        this.clock = clock;
        this.recipient = recipient;
        this.threshold = threshold;
        this.window = window;
        this.cooldown = cooldown;
    }

    @Scheduled(fixedDelayString = "${app.security-alerts.interval:PT1M}")
    public void notifyOnAnomalies() {
        if (recipient.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        nextAlert.values().removeIf(expiry -> !expiry.isAfter(now));
        for (var group : entries.findAnomalyGroups(now.minus(window), now, AuditAction.LOGIN,
                AuditOutcome.FAILURE, AuditAction.ACCESS_DENIED, threshold)) {
            Source source = new Source(group.getAction(), group.getUserId(), group.getIpAddress());
            if (nextAlert.containsKey(source)) {
                continue;
            }
            String text = """
                    Événement : %s
                    Identifiant du compte : %s
                    Adresse IP source : %s
                    Dernier événement (UTC) : %s
                    Nombre d’événements : %d
                    """.formatted(source.action(), Objects.toString(source.userId(), "inconnu"),
                    Objects.toString(source.ipAddress(), "inconnue"), group.getLastSeen(), group.getTotal());
            try {
                mail.sendSecurityAlert(recipient, text);
                nextAlert.put(source, now.plus(cooldown));
            } catch (MailException e) {
                LOG.warn("Security alert delivery failed ({})", e.getClass().getSimpleName());
            }
        }
    }
}
