package com.example.apimedlink.security;

import java.util.List;

/** The three MedLink roles (Keycloak realm roles). Use these constants, never a typed string. */
public final class Roles {

    public static final String PATIENT = "PATIENT";
    public static final String PRACTITIONER = "PRACTITIONER";
    public static final String ADMINISTRATIVE_STAFF = "ADMINISTRATIVE_STAFF";

    /** Every MedLink role; anything else in Keycloak's roles claim is not ours. */
    public static final List<String> ALL = List.of(PATIENT, PRACTITIONER, ADMINISTRATIVE_STAFF);

    private Roles() {}
}
