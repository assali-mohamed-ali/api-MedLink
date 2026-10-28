package com.example.apimedlink.service;

import com.example.apimedlink.model.EmailToken.Purpose;
import com.example.apimedlink.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;

/**
 * The link a practitioner or staff member needs for their first staff-code setup (MLink-62, review of #97): mailed
 * to their own address, so a stolen password alone cannot enrol a code. Valid 30 minutes
 * ({@link Purpose#MFA_ENROLMENT}), at most one mail per address per 15 minutes. The token is never logged.
 */
@Service
public class MfaEnrolmentLinks {

    private static final Logger log = LoggerFactory.getLogger(MfaEnrolmentLinks.class);
    static final String MAIL_KIND = "mfa-enrolment";

    private final EmailTokenService tokens;
    private final MailService mail;
    private final MailThrottle throttle;

    public MfaEnrolmentLinks(EmailTokenService tokens, MailService mail, MailThrottle throttle) {
        this.tokens = tokens;
        this.mail = mail;
        this.throttle = throttle;
    }

    /**
     * Mails a new link, older ones stop working. Within the interval nothing is sent and the last link keeps
     * working. Never throws for the mail: the login answers the same either way.
     */
    public void send(User user, String firstName) {
        if (!throttle.allow(MAIL_KIND, user.getEmail())) {
            return;
        }
        tokens.cancelUnused(user.getId(), Purpose.MFA_ENROLMENT);
        String token = tokens.issue(user.getId(), Purpose.MFA_ENROLMENT);
        try {
            mail.sendMfaEnrolmentLink(user.getEmail(), firstName, token);
            log.info("Staff code setup link mailed to user {}", user.getId());
        } catch (MailException e) {
            log.error("Staff code setup link could not be mailed to user {} ({})", user.getId(),
                    e.getClass().getSimpleName());
        }
    }
}
