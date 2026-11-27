package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Registration requires the current policy version in staging and production. */
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
    private final boolean available;

    public ConsentPolicy(@Value("${app.consent.required:false}") boolean required,
            @Value("${app.consent.available:true}") boolean available) {
        this.required = required;
        this.available = available;
    }

    public void requireAvailable() {
        if (!available) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CONSENT_UNAVAILABLE",
                    "Les inscriptions sont momentanément indisponibles.");
        }
    }

    public boolean required() {
        return required;
    }

    /** Throws a 400 naming {@code consentVersion} when the consent is missing (and required) or is an old text. */
    public void check(String providedVersion) {
        requireAvailable();
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
