package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Registration requires the current policy version in staging and production. */
@Component
public class ConsentPolicy {

    public static final String CURRENT_VERSION = "2026-11-v2";

    public static final String TEXT = "Je consens expressément au traitement de mes données personnelles et de santé "
            + "par MedLink et les professionnels habilités de ma clinique, pour gérer mes rendez-vous et mon dossier "
            + "médical, conformément à la loi organique n° 2004-63. L'accès est limité selon les fonctions et le secret "
            + "professionnel. Les données sont conservées selon les obligations légales et les conditions fixées "
            + "par l'INPDP, notamment les délais applicables au dossier médical. Je peux demander l'accès, la "
            + "rectification, une copie ou l'effacement de mes données, dans les limites de ces obligations, et "
            + "retirer mon consentement auprès de ma clinique. Je peux saisir l'Instance nationale de protection "
            + "des données personnelles (INPDP). L'inscription nécessite mon accord explicite.";

    private final boolean required;
    private final boolean available;

    public ConsentPolicy(@Value("${app.consent.required:true}") boolean required,
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
        if (providedVersion == null || providedVersion.isBlank()) {
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
