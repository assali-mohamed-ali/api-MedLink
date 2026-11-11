package com.example.apimedlink.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/** The mails the API sends, in French: plain text plus simple HTML. Links open React pages. */
@Service
public class MailService {

    private final JavaMailSender mailSender;
    private final String from;
    private final String frontendUrl;

    public MailService(
            JavaMailSender mailSender,
            @Value("${app.mail.from}") String from,
            @Value("${app.frontend-url}") String frontendUrl) {
        this.mailSender = mailSender;
        this.from = from;
        this.frontendUrl = frontendUrl;
    }

    /** Throws {@link org.springframework.mail.MailException} when the mail cannot be sent. */
    public void sendEmailVerification(String to, String firstName, String token) {
        String link = UriComponentsBuilder.fromUriString(frontendUrl)
                .path("/verifier-email")
                .queryParam("token", token)
                .build()
                .toUriString();
        String hello = firstName == null || firstName.isBlank() ? "Bonjour," : "Bonjour " + firstName + ",";
        String text = hello + "\n\n"
                + "Bienvenue sur MedLink. Pour activer votre compte, confirmez votre adresse e-mail en ouvrant ce lien :\n\n"
                + link + "\n\n"
                + "Ce lien est valable 24 heures et ne peut servir qu'une fois.\n"
                + "Si vous n'avez pas créé de compte MedLink, ignorez ce message.\n\n"
                + "L'équipe MedLink";
        String html = "<p>" + HtmlUtils.htmlEscape(hello) + "</p>"
                + "<p>Bienvenue sur MedLink. Pour activer votre compte, confirmez votre adresse e-mail :</p>"
                + "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">Confirmer mon adresse e-mail</a></p>"
                + "<p>Ce lien est valable 24 heures et ne peut servir qu'une fois.<br>"
                + "Si vous n'avez pas créé de compte MedLink, ignorez ce message.</p>"
                + "<p>L'équipe MedLink</p>";
        send(to, "Confirmez votre adresse e-mail MedLink", text, html);
    }

    /**
     * A practitioner's or staff member's first staff-code setup (MLink-62): only the owner of the mailbox gets
     * past it, so a stolen password alone cannot enrol a code. Throws like {@link #sendEmailVerification}.
     */
    public void sendMfaEnrolmentLink(String to, String firstName, String token) {
        String link = UriComponentsBuilder.fromUriString(frontendUrl)
                .path("/configurer-code")
                .queryParam("token", token)
                .build()
                .toUriString();
        String hello = firstName == null || firstName.isBlank() ? "Bonjour," : "Bonjour " + firstName + ",";
        String text = hello + "\n\n"
                + "Vous venez de vous connecter à MedLink pour la première fois. Pour protéger votre compte, "
                + "configurez votre code de connexion (application d'authentification) en ouvrant ce lien :\n\n"
                + link + "\n\n"
                + "Ce lien est valable 30 minutes et sert jusqu'à ce que votre code soit configuré.\n"
                + "Si ce n'était pas vous, quelqu'un connaît votre mot de passe : changez-le et prévenez "
                + "l'administration.\n\n"
                + "L'équipe MedLink";
        String html = "<p>" + HtmlUtils.htmlEscape(hello) + "</p>"
                + "<p>Vous venez de vous connecter à MedLink pour la première fois. Pour protéger votre compte, "
                + "configurez votre code de connexion (application d'authentification) :</p>"
                + "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">Configurer mon code de connexion</a></p>"
                + "<p>Ce lien est valable 30 minutes et sert jusqu'à ce que votre code soit configuré.<br>"
                + "Si ce n'était pas vous, quelqu'un connaît votre mot de passe : changez-le et prévenez "
                + "l'administration.</p>"
                + "<p>L'équipe MedLink</p>";
        send(to, "Configurez votre code de connexion MedLink", text, html);
    }

    /**
     * Someone tried to sign up with an address that already has an account. Only the owner learns
     * it; the person who tried sees the usual "check your mailbox" answer.
     */
    public void sendSignupAttemptOnExistingAccount(String to) {
        String text = "Bonjour,\n\n"
                + "Quelqu'un vient d'essayer de créer un compte MedLink avec votre adresse e-mail. "
                + "Vous avez déjà un compte : aucun nouveau compte n'a été créé et le vôtre n'a pas été modifié.\n\n"
                + "Si c'était vous, connectez-vous avec votre mot de passe habituel. Si vous n'avez pas encore "
                + "confirmé votre adresse, demandez un nouveau lien de confirmation depuis la page de connexion.\n"
                + "Si ce n'était pas vous, ignorez ce message.\n\n"
                + "L'équipe MedLink";
        String html = "<p>Bonjour,</p>"
                + "<p>Quelqu'un vient d'essayer de créer un compte MedLink avec votre adresse e-mail. "
                + "Vous avez déjà un compte : aucun nouveau compte n'a été créé et le vôtre n'a pas été modifié.</p>"
                + "<p>Si c'était vous, connectez-vous avec votre mot de passe habituel. Si vous n'avez pas encore "
                + "confirmé votre adresse, demandez un nouveau lien de confirmation depuis la page de connexion.<br>"
                + "Si ce n'était pas vous, ignorez ce message.</p>"
                + "<p>L'équipe MedLink</p>";
        send(to, "Tentative d'inscription avec votre adresse e-mail MedLink", text, html);
    }

    private void send(String to, String subject, String text, String html) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
        } catch (MessagingException e) {
            throw new MailPreparationException(e);
        }
        mailSender.send(message);
    }
}
