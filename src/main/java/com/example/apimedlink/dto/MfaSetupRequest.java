package com.example.apimedlink.dto;

/**
 * Endpoint 8: the token of the setup link mailed at this login (review of #97), so a stolen password alone
 * enrols nothing. Checked by the controller after the pending code step, so a caller without one gets 401.
 */
public record MfaSetupRequest(String token) {

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    /** Never print the token, whoever logs this object. */
    @Override
    public String toString() {
        return "MfaSetupRequest[token=***]";
    }
}
