package com.example.apimedlink.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Endpoint 5: a full login returns {@code { "user": {…} }}; a practitioner or staff member returns only
 * {@code { "mfaRequired": true, "mfaSetupRequired": true|false }} until the code step (endpoint 9); a forced
 * change returns only {@code { "passwordChangeRequired": true }}. No token ever reaches the browser.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(SessionUserDto user, Boolean passwordChangeRequired, Boolean mfaRequired,
                            Boolean mfaSetupRequired) {

    public static LoginResponse of(SessionUserDto user) {
        return new LoginResponse(user, null, null, null);
    }

    public static LoginResponse forcedPasswordChange() {
        return new LoginResponse(null, true, null, null);
    }

    public static LoginResponse mfaRequired(boolean setupRequired) {
        return new LoginResponse(null, null, true, setupRequired);
    }
}
