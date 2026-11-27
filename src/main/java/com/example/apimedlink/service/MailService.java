package com.example.apimedlink.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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

    /** The clinics are in Tunisia: a reminder shows their local date and time. */
    static final ZoneId CLINICS_ZONE = ZoneId.of("Africa/Tunis");
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH);
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH'h'mm", Locale.FRENCH);

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

    /** US-03: the link to choose a new password. Throws like {@link #sendEmailVerification}. */
    public void sendPasswordReset(String to, String firstName, String token) {
        String link = UriComponentsBuilder.fromUriString(frontendUrl)
                .path("/reinitialiser-mot-de-passe")
                .queryParam("token", token)
                .build()
                .toUriString();
        String hello = firstName == null || firstName.isBlank() ? "Bonjour," : "Bonjour " + firstName + ",";
        String text = hello + "\n\n"
                + "Vous avez demandé à réinitialiser votre mot de passe MedLink. Ouvrez ce lien pour en choisir un nouveau :\n\n"
                + link + "\n\n"
                + "Ce lien est valable 1 heure et ne peut servir qu'une fois.\n"
                + "Si vous n'êtes pas à l'origine de cette demande, ignorez ce message : votre mot de passe ne change pas.\n\n"
                + "L'équipe MedLink";
        String html = "<p>" + HtmlUtils.htmlEscape(hello) + "</p>"
                + "<p>Vous avez demandé à réinitialiser votre mot de passe MedLink.</p>"
                + "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">Choisir un nouveau mot de passe</a></p>"
                + "<p>Ce lien est valable 1 heure et ne peut servir qu'une fois.<br>"
                + "Si vous n'êtes pas à l'origine de cette demande, ignorez ce message : votre mot de passe ne change pas.</p>"
                + "<p>L'équipe MedLink</p>";
        send(to, "Réinitialisation de votre mot de passe MedLink", text, html);
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
     * A staff account an admin created (MLink-71): its owner opens the link and chooses the first password.
     * Throws like {@link #sendEmailVerification}.
     */
    public void sendAccountActivation(String to, String firstName, String token) {
        String link = UriComponentsBuilder.fromUriString(frontendUrl)
                .path("/activer-compte")
                .queryParam("token", token)
                .build()
                .toUriString();
        String hello = firstName == null || firstName.isBlank() ? "Bonjour," : "Bonjour " + firstName + ",";
        String text = hello + "\n\n"
                + "Un compte MedLink vient d'être créé pour vous. Pour l'activer, choisissez votre mot de passe "
                + "en ouvrant ce lien :\n\n"
                + link + "\n\n"
                + "Ce lien est valable 24 heures et ne peut servir qu'une fois.\n"
                + "Si vous n'attendiez pas ce message, ignorez-le.\n\n"
                + "L'équipe MedLink";
        String html = "<p>" + HtmlUtils.htmlEscape(hello) + "</p>"
                + "<p>Un compte MedLink vient d'être créé pour vous. Pour l'activer, choisissez votre mot de passe :</p>"
                + "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">Activer mon compte</a></p>"
                + "<p>Ce lien est valable 24 heures et ne peut servir qu'une fois.<br>"
                + "Si vous n'attendiez pas ce message, ignorez-le.</p>"
                + "<p>L'équipe MedLink</p>";
        send(to, "Activez votre compte MedLink", text, html);
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

    /**
     * MLink-37 (37.4, H-06): a patient cancelled one of the practitioner's appointments. Who, when (Tunis time) and
     * where, with a link to the agenda: never medical content. Throws like {@link #sendEmailVerification}.
     */
    public void sendAppointmentCancelled(String to, String firstName, String patient, String when, String clinic) {
        String agenda = UriComponentsBuilder.fromUriString(frontendUrl).path("/praticien/agenda").build().toUriString();
        String hello = firstName == null || firstName.isBlank() ? "Bonjour," : "Bonjour " + firstName + ",";
        String text = hello + "\n\n"
                + "Un rendez-vous de votre agenda vient d'être annulé par le patient :\n\n"
                + "Patient : " + patient + "\n"
                + "Date : " + when + " (heure de Tunis)\n"
                + "Clinique : " + clinic + "\n\n"
                + "Le créneau est de nouveau libre. Votre agenda : " + agenda + "\n\n"
                + "L'équipe MedLink";
        String html = "<p>" + HtmlUtils.htmlEscape(hello) + "</p>"
                + "<p>Un rendez-vous de votre agenda vient d'être annulé par le patient :</p>"
                + "<ul><li>Patient : " + HtmlUtils.htmlEscape(patient) + "</li>"
                + "<li>Date : " + HtmlUtils.htmlEscape(when) + " (heure de Tunis)</li>"
                + "<li>Clinique : " + HtmlUtils.htmlEscape(clinic) + "</li></ul>"
                + "<p>Le créneau est de nouveau libre. <a href=\"" + HtmlUtils.htmlEscape(agenda) + "\">Ouvrir mon agenda</a></p>"
                + "<p>L'équipe MedLink</p>";
        send(to, "Un rendez-vous a été annulé", text, html);
    }

    /**
     * MLink-37: a patient moved one of the practitioner's appointments to another date. Who, the old date (Tunis time) and
     * clinic, the new date, with a link to the agenda: never medical content. Throws like {@link #sendEmailVerification}.
     */
    public void sendAppointmentMoved(String to, String firstName, String patient, String fromWhen, String fromClinic,
            String toWhen) {
        String agenda = UriComponentsBuilder.fromUriString(frontendUrl).path("/praticien/agenda").build().toUriString();
        String hello = firstName == null || firstName.isBlank() ? "Bonjour," : "Bonjour " + firstName + ",";
        String text = hello + "\n\n"
                + "Un rendez-vous de votre agenda vient d'être reporté par le patient :\n\n"
                + "Patient : " + patient + "\n"
                + "Ancienne date : " + fromWhen + " (heure de Tunis), " + fromClinic + "\n"
                + "Nouvelle date : " + toWhen + " (heure de Tunis)\n\n"
                + "L'ancien créneau est de nouveau libre. Votre agenda : " + agenda + "\n\n"
                + "L'équipe MedLink";
        String html = "<p>" + HtmlUtils.htmlEscape(hello) + "</p>"
                + "<p>Un rendez-vous de votre agenda vient d'être reporté par le patient :</p>"
                + "<ul><li>Patient : " + HtmlUtils.htmlEscape(patient) + "</li>"
                + "<li>Ancienne date : " + HtmlUtils.htmlEscape(fromWhen) + " (heure de Tunis), " + HtmlUtils.htmlEscape(fromClinic) + "</li>"
                + "<li>Nouvelle date : " + HtmlUtils.htmlEscape(toWhen) + " (heure de Tunis)</li></ul>"
                + "<p>L'ancien créneau est de nouveau libre. <a href=\"" + HtmlUtils.htmlEscape(agenda) + "\">Ouvrir mon agenda</a></p>"
                + "<p>L'équipe MedLink</p>";
        send(to, "Un rendez-vous a été reporté", text, html);
    }

    /**
     * MLink-110: the reminder before an appointment. Where, with whom and when only: never a medical detail. The date
     * and time are Tunisia's, where the clinics are. Throws like {@link #sendEmailVerification}.
     * MLink-111 (review on #120): the Message-ID is the appointment's, the same at every try, so a reminder sent again
     * after a crash (at least once, never "exactly once" over SMTP) is the same message for the mail systems.
     */
    public void sendAppointmentReminder(String to, String firstName, String clinic, String practitioner, Instant startsAt,
            UUID appointmentId) {
        String link = UriComponentsBuilder.fromUriString(frontendUrl).path("/patient/rendez-vous").build().toUriString();
        String hello = firstName == null || firstName.isBlank() ? "Bonjour," : "Bonjour " + firstName + ",";
        ZonedDateTime local = startsAt.atZone(CLINICS_ZONE);
        String day = local.format(DAY);
        String time = local.format(TIME);
        String text = hello + "\n\n"
                + "Nous vous rappelons votre rendez-vous :\n\n"
                + "Date : " + day + "\n"
                + "Heure : " + time + " (heure de Tunisie)\n"
                + "Praticien : " + practitioner + "\n"
                + "Clinique : " + clinic + "\n\n"
                + "Si vous ne pouvez pas venir, annulez ou reportez ce rendez-vous depuis votre espace :\n\n"
                + link + "\n\n"
                + "L'équipe MedLink";
        String html = "<p>" + HtmlUtils.htmlEscape(hello) + "</p>"
                + "<p>Nous vous rappelons votre rendez-vous :</p>"
                + "<p>Date : <strong>" + HtmlUtils.htmlEscape(day) + "</strong><br>"
                + "Heure : <strong>" + HtmlUtils.htmlEscape(time) + "</strong> (heure de Tunisie)<br>"
                + "Praticien : " + HtmlUtils.htmlEscape(practitioner) + "<br>"
                + "Clinique : " + HtmlUtils.htmlEscape(clinic) + "</p>"
                + "<p>Si vous ne pouvez pas venir, annulez ou reportez ce rendez-vous depuis votre espace :</p>"
                + "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">Voir mes rendez-vous</a></p>"
                + "<p>L'équipe MedLink</p>";
        send(to, "Rappel de votre rendez-vous MedLink", text, html, reminderMessageId(appointmentId));
    }

    /**
     * US-08: the confirmation of a booking or of a move, sent to the patient once it is committed. Same content as the
     * reminder; the Message-ID is the appointment's (one confirmation per appointment, the same at every try).
     */
    public void sendAppointmentConfirmation(String to, String firstName, String clinic, String practitioner,
            Instant startsAt, UUID appointmentId) {
        String link = UriComponentsBuilder.fromUriString(frontendUrl).path("/patient/rendez-vous").build().toUriString();
        String hello = firstName == null || firstName.isBlank() ? "Bonjour," : "Bonjour " + firstName + ",";
        ZonedDateTime local = startsAt.atZone(CLINICS_ZONE);
        String day = local.format(DAY);
        String time = local.format(TIME);
        String text = hello + "\n\n"
                + "Votre rendez-vous est confirmé :\n\n"
                + "Date : " + day + "\n"
                + "Heure : " + time + " (heure de Tunisie)\n"
                + "Praticien : " + practitioner + "\n"
                + "Clinique : " + clinic + "\n\n"
                + "Vous pouvez l'annuler ou le reporter depuis votre espace :\n\n"
                + link + "\n\n"
                + "L'équipe MedLink";
        String html = "<p>" + HtmlUtils.htmlEscape(hello) + "</p>"
                + "<p>Votre rendez-vous est confirmé :</p>"
                + "<p>Date : <strong>" + HtmlUtils.htmlEscape(day) + "</strong><br>"
                + "Heure : <strong>" + HtmlUtils.htmlEscape(time) + "</strong> (heure de Tunisie)<br>"
                + "Praticien : " + HtmlUtils.htmlEscape(practitioner) + "<br>"
                + "Clinique : " + HtmlUtils.htmlEscape(clinic) + "</p>"
                + "<p>Vous pouvez l'annuler ou le reporter depuis votre espace :</p>"
                + "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">Voir mes rendez-vous</a></p>"
                + "<p>L'équipe MedLink</p>";
        send(to, "Confirmation de votre rendez-vous MedLink", text, html,
                "<appointment-confirmation-" + appointmentId + "@medlink.local>");
    /** US-21: the practitioner is absent and cancelled the appointment. Throws like {@link #sendEmailVerification}. */
    public void sendAppointmentCancelledByPractitioner(String to, String firstName, String practitioner, String clinic,
            Instant startsAt) {
        ZonedDateTime local = startsAt.atZone(CLINICS_ZONE);
        sendNotice(to, firstName, "Votre rendez-vous MedLink est annulé", List.of(
                "Votre praticien est absent : votre rendez-vous est annulé.",
                "Date : " + local.format(DAY) + " à " + local.format(TIME) + " (heure de Tunisie)",
                "Praticien : " + practitioner,
                "Clinique : " + clinic,
                "Vous pouvez réserver un autre créneau depuis votre espace."));
    }

    /** US-21: the practitioner is absent and moved the appointment to the same time next week. */
    public void sendAppointmentPostponedByPractitioner(String to, String firstName, String practitioner, String clinic,
            Instant oldStart, Instant newStart) {
        ZonedDateTime from = oldStart.atZone(CLINICS_ZONE);
        ZonedDateTime to2 = newStart.atZone(CLINICS_ZONE);
        sendNotice(to, firstName, "Votre rendez-vous MedLink est reporté", List.of(
                "Votre praticien est absent : votre rendez-vous est reporté à la semaine suivante.",
                "Ancienne date : " + from.format(DAY) + " à " + from.format(TIME) + " (heure de Tunisie)",
                "Nouvelle date : " + to2.format(DAY) + " à " + to2.format(TIME) + " (heure de Tunisie)",
                "Praticien : " + practitioner,
                "Clinique : " + clinic,
                "Si cette date ne vous convient pas, annulez ou reportez le rendez-vous depuis votre espace."));
    }

    private void sendNotice(String to, String firstName, String subject, List<String> lines) {
        String link = UriComponentsBuilder.fromUriString(frontendUrl).path("/patient/rendez-vous").build().toUriString();
        String hello = firstName == null || firstName.isBlank() ? "Bonjour," : "Bonjour " + firstName + ",";
        StringBuilder text = new StringBuilder(hello).append("\n\n");
        StringBuilder html = new StringBuilder("<p>").append(HtmlUtils.htmlEscape(hello)).append("</p>");
        for (String line : lines) {
            text.append(line).append("\n");
            html.append("<p>").append(HtmlUtils.htmlEscape(line)).append("</p>");
        }
        text.append("\n").append(link).append("\n\nL'équipe MedLink");
        html.append("<p><a href=\"").append(HtmlUtils.htmlEscape(link)).append("\">Voir mes rendez-vous</a></p>")
                .append("<p>L'équipe MedLink</p>");
        send(to, subject, text.toString(), html.toString());
    }

    /** The Message-ID of an appointment's reminder: one per appointment, whatever the try. */
    public static String reminderMessageId(UUID appointmentId) {
        return "<appointment-reminder-" + appointmentId + "@medlink.local>";
    }

    void sendSecurityAlert(String to, String text) {
        send(to, "Alerte de sécurité MedLink", text, "<pre>" + HtmlUtils.htmlEscape(text) + "</pre>");
    }

    private void send(String to, String subject, String text, String html) {
        send(to, subject, text, html, null);
    }

    /** {@code messageId} null: the mail library makes one. Spring keeps a Message-ID set before sending. */
    private void send(String to, String subject, String text, String html, String messageId) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            if (messageId != null) {
                message.setHeader("Message-ID", messageId);
            }
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
