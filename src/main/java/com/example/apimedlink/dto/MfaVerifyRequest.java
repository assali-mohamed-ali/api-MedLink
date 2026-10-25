package com.example.apimedlink.dto;

import java.util.regex.Pattern;

/**
 * Endpoint 9: the 6-digit code from the authenticator app. Checked by the controller after the pending code
 * step, so a caller without one gets 401 whatever it sends (like endpoint 10).
 */
public record MfaVerifyRequest(String code) {

    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

    public boolean isSixDigits() {
        return code != null && SIX_DIGITS.matcher(code).matches();
    }

    /** Never print the code, whoever logs this object. */
    @Override
    public String toString() {
        return "MfaVerifyRequest[code=***]";
    }
}
