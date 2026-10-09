package com.example.apimedlink.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth BFF (Backend For Frontend): the SPA talks only to this backend, which holds the
 * Keycloak tokens server-side. Login itself is handled by Spring's oauth2Login
 * ({@code /oauth2/authorization/keycloak}).
 *
 * <p>Skeleton only — endpoints (current user, logout, CSRF bootstrap) are not implemented yet.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthBffController {}
