package com.example.apimedlink.service;

import com.example.apimedlink.model.Alert;
import com.example.apimedlink.repository.AlertRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A practitioner's alerts (MLink-37). Every query is scoped to the caller: an alert of someone else looks exactly like
 * one that does not exist, so a refusal tells nothing about other practitioners' alerts. Takes and returns entities.
 */
@Service
public class AlertService {

    private final AlertRepository alerts;
    private final Clock clock;

    public AlertService(AlertRepository alerts, Clock clock) {
        this.alerts = alerts;
        this.clock = clock;
    }

    /** The caller's alerts, newest first: the unread ones (the bell), or all of them. */
    @Transactional(readOnly = true)
    public List<Alert> list(UUID practitionerId, boolean unreadOnly) {
        return unreadOnly
                ? alerts.findByPractitionerIdAndReadAtIsNullOrderByCreatedAtDesc(practitionerId)
                : alerts.findByPractitionerIdOrderByCreatedAtDesc(practitionerId);
    }

    /**
     * Marks one of the caller's alerts read. Marking it again changes nothing (the first reading time stays). Another
     * practitioner's alert and an unknown one raise the same {@link AccessDeniedException} (403, audited as a refusal).
     */
    @Transactional
    public void markRead(UUID practitionerId, UUID alertId) {
        Alert alert = alerts.findByIdAndPractitionerId(alertId, practitionerId)
                .orElseThrow(() -> new AccessDeniedException("Not the practitioner's alert"));
        alert.markRead(clock.instant());
    }
}
