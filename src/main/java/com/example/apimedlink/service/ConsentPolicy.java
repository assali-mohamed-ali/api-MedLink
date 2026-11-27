package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * US-04: the consent text patients accept when they sign up, and its version. The version is recorded with the patient.
 * {@code app.consent.required} (true in staging and production): a sign-up without consent is refused. A version that
 * is not the current one is always refused: the visitor must read the current text. The wording was confirmed by the code owner, who asked for
 * it to follow Tunisian law: it is based on Organic Law 2004-63 and the INPDP guide for doctors (consent, access by persons
 * bound by professional secrecy, hosting in Tunisia, 20-year retention, rights, INPDP). To be reviewed by a lawyer before
 * production (docs/product-decisions.md).
 */
@Component
public class ConsentPolicy {

    public static final String CURRENT_VERSION = "2026-11-v1";

    public static final String TEXT = "Je consens expressément à ce que MedLink traite mes données personnelles et de santé "
            + "pour gérer mes rendez-vous et mon dossier médical, conformément à la loi organique n° 2004-63 relative à "
            + "la protection des données à caractère personnel. Mes données de santé ne sont accessibles qu'aux "
            + "professionnels de santé qui me suivent et aux personnes tenues au secret professionnel, et elles sont "
            + "hébergées en Tunisie. Elles sont conservées pendant 20 ans à compter de ma dernière consultation, puis "
            + "détruites. Je peux demander l'accès à mes données, leur rectification, leur export ou leur suppression "
            + "depuis mon profil, et je peux saisir l'Instance nationale de protection des données personnelles (INPDP) "
            + "en cas de désaccord.";

    private final boolean required;

    public ConsentPolicy(@Value("${app.consent.required:false}") boolean required) {
        this.required = required;
    }

    public boolean required() {
        return required;
    }

    /** Throws a 400 naming {@code consentVersion} when the consent is missing (and required) or is an old text. */
    public void check(String providedVersion) {
        if (providedVersion == null) {
            if (required) {
                throw refused("Vous devez accepter le consentement pour créer un compte.");
            }
            return;
        }
        if (!CURRENT_VERSION.equals(providedVersion)) {
            throw refused("Le texte du consentement a changé : relisez-le puis acceptez-le.");
        }
    }

    private static ApiException refused(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Certains champs sont invalides.",
                Map.of("consentVersion", message));
    }
}
