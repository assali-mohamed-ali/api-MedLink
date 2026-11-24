package com.example.apimedlink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.apimedlink.model.EmailToken.Purpose;
import com.example.apimedlink.model.User;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;

/**
 * The staff code's first-setup link (MLink-62, review of #97): the branches a functional test cannot reach on
 * demand, a throttled address and a mail server that fails. Neither may change the login's answer.
 */
@ExtendWith(OutputCaptureExtension.class)
class MfaEnrolmentLinksTest {

    final EmailTokenService tokens = mock(EmailTokenService.class);
    final MailService mail = mock(MailService.class);
    final MailThrottle throttle = mock(MailThrottle.class);
    final MfaEnrolmentLinks links = new MfaEnrolmentLinks(tokens, mail, throttle);

    final UUID id = UUID.randomUUID();
    final User user = User.builder().id(id).username("staff@example.com").email("staff@example.com").build();

    @Test
    void aSendClosesOlderLinksThenMailsANewOne() {
        when(throttle.allow(MfaEnrolmentLinks.MAIL_KIND, "staff@example.com")).thenReturn(true);
        when(tokens.issue(id, Purpose.MFA_ENROLMENT)).thenReturn("the-token");

        links.send(user, "Amel");

        InOrder order = inOrder(tokens, mail);
        order.verify(tokens).cancelUnused(id, Purpose.MFA_ENROLMENT);
        order.verify(tokens).issue(id, Purpose.MFA_ENROLMENT);
        order.verify(mail).sendMfaEnrolmentLink("staff@example.com", "Amel", "the-token");
    }

    @Test
    void aThrottledAddressGetsNothingAndItsLinkKeepsWorking() {
        when(throttle.allow(MfaEnrolmentLinks.MAIL_KIND, "staff@example.com")).thenReturn(false);

        links.send(user, "Amel");

        verify(tokens, never()).cancelUnused(any(), any());
        verify(tokens, never()).issue(any(), any());
        verify(mail, never()).sendMfaEnrolmentLink(anyString(), anyString(), anyString());
    }

    @Test
    void aMailFailureIsLoggedWithoutTheTokenAndNeverReachesTheLogin(CapturedOutput output) {
        when(throttle.allow(MfaEnrolmentLinks.MAIL_KIND, "staff@example.com")).thenReturn(true);
        when(tokens.issue(id, Purpose.MFA_ENROLMENT)).thenReturn("secret-token-value");
        doThrow(new MailSendException("smtp down")).when(mail)
                .sendMfaEnrolmentLink("staff@example.com", "Amel", "secret-token-value");

        assertThatCode(() -> links.send(user, "Amel")).doesNotThrowAnyException();

        assertThat(output).contains("user " + id).contains("MailSendException").doesNotContain("secret-token-value");
    }
}
