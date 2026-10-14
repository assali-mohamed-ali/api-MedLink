package com.example.apimedlink.security;

/** The three MedLink roles (Keycloak realm roles). Use these constants, never a typed string. */
public final class Roles {

    public static final String PATIENT = "PATIENT";
    public static final String PRACTITIONER = "PRACTITIONER";
    public static final String ADMINISTRATIVE_STAFF = "ADMINISTRATIVE_STAFF";

    private Roles() {}
}
