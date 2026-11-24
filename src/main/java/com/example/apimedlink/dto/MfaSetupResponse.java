package com.example.apimedlink.dto;

/**
 * Endpoint 8: what the authenticator app needs, as a QR code (a PNG data URI of {@code otpauthUrl}) or typed in
 * ({@code secret}, Base32). Sent once, to the user at the code step; never logged.
 */
public record MfaSetupResponse(String otpauthUrl, String qrCode, String secret) {

    @Override
    public String toString() {
        return "MfaSetupResponse[secret=***]";
    }
}
