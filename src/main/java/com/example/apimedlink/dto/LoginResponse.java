package com.example.apimedlink.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Endpoint 5: a full login returns {@code { "user": {…} }}; a forced change returns only
 * {@code { "passwordChangeRequired": true }}. No token ever reaches the browser.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(SessionUserDto user, Boolean passwordChangeRequired) {

    public static LoginResponse forcedPasswordChange() {
        return new LoginResponse(null, true);
    }
}
