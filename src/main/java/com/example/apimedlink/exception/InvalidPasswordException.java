package com.example.apimedlink.exception;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Keycloak refused a password because of the realm's policy. Keeps Keycloak's error key
 * (e.g. {@code invalidPasswordMinDigitsMessage}) and tells the user the rule in French.
 */
public class InvalidPasswordException extends ApiException {

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private final String errorKey;

    public InvalidPasswordException(String errorKey, String keycloakDescription) {
        super(HttpStatus.BAD_REQUEST, "PASSWORD_POLICY", frenchMessage(errorKey, keycloakDescription),
                Map.of("password", frenchMessage(errorKey, keycloakDescription)));
        this.errorKey = errorKey;
    }

    public String getErrorKey() {
        return errorKey;
    }

    /** Keycloak puts the rule's number in its English description ("minimum length 12."). */
    static String frenchMessage(String errorKey, String description) {
        int n = 1;
        if (description != null) {
            Matcher m = NUMBER.matcher(description);
            if (m.find()) {
                n = Integer.parseInt(m.group());
            }
        }
        String s = n > 1 ? "s" : "";
        String key = errorKey == null ? "" : errorKey;
        switch (key) {
            case "invalidPasswordMinLengthMessage":
                return "Le mot de passe doit contenir au moins " + n + " caractères.";
            case "invalidPasswordMaxLengthMessage":
                return "Le mot de passe doit contenir au plus " + n + " caractères.";
            case "invalidPasswordMinUpperCaseCharsMessage":
                return "Le mot de passe doit contenir au moins " + n + " majuscule" + s + ".";
            case "invalidPasswordMinLowerCaseCharsMessage":
                return "Le mot de passe doit contenir au moins " + n + " minuscule" + s + ".";
            case "invalidPasswordMinDigitsMessage":
                return "Le mot de passe doit contenir au moins " + n + " chiffre" + s + ".";
            case "invalidPasswordMinSpecialCharsMessage":
                return "Le mot de passe doit contenir au moins " + n + " caractère" + s + " spécia"
                        + (n > 1 ? "ux" : "l") + " (par exemple ! ? @ #).";
            case "invalidPasswordNotUsernameMessage":
                return "Le mot de passe ne doit pas être votre identifiant.";
            case "invalidPasswordNotEmailMessage":
                return "Le mot de passe ne doit pas être votre adresse e-mail.";
            case "invalidPasswordHistoryMessage":
                return "Le mot de passe doit être différent de vos " + n + " derniers mots de passe.";
            case "invalidPasswordBlacklistedMessage":
                return "Ce mot de passe est trop courant. Choisissez-en un autre.";
            default:
                return "Le mot de passe ne respecte pas les règles de sécurité.";
        }
    }
}
