package com.example.apimedlink.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.apimedlink.model.AuditAction;
import com.example.apimedlink.model.AuditOutcome;
import com.example.apimedlink.repository.AuditEntryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnomalyAlertServiceTest {
    private final Instant now = Instant.parse("2026-11-27T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final AuditEntryRepository entries = mock(AuditEntryRepository.class);
    private final MailService mail = mock(MailService.class);
    private final Duration window = Duration.ofMinutes(10);
    private final Duration cooldown = Duration.ofMinutes(30);

    @Test
    void sendsOnlyOncePerSourceWithinTheCooldown() {
        var group = mock(AuditEntryRepository.AnomalyGroup.class);
        when(group.getAction()).thenReturn(AuditAction.LOGIN);
        when(group.getIpAddress()).thenReturn("192.0.2.1");
        when(group.getLastSeen()).thenReturn(now);
        when(group.getTotal()).thenReturn(7L);
        when(entries.findAnomalyGroups(now.minus(window), now, AuditAction.LOGIN, AuditOutcome.FAILURE,
                AuditAction.ACCESS_DENIED, 5)).thenReturn(List.of(group));
        var service = new AnomalyAlertService(entries, mail, clock, "ops@example.com", 5, window, cooldown);

        service.notifyOnAnomalies();
        service.notifyOnAnomalies();

        verify(mail, times(1)).sendSecurityAlert(anyString(), anyString());
        verify(mail).sendSecurityAlert(eq("ops@example.com"), contains("192.0.2.1"));
    }

    @Test
    void leavesAuditDataAloneWhenNoRecipientIsConfigured() {
        new AnomalyAlertService(entries, mail, clock, "", 5, window, cooldown).notifyOnAnomalies();

        verifyNoInteractions(entries, mail);
    }
}
