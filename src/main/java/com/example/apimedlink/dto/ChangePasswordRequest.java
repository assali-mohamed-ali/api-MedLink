package com.example.apimedlink.dto;

/** Endpoint 10: a session change carries both passwords; a forced change carries only the new one. */
public record ChangePasswordRequest(String currentPassword, String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[passwords=***]";
    }
}
