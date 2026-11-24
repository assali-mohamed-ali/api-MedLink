package com.example.apimedlink.service;

import com.example.apimedlink.model.User;
import java.util.List;

/** A login Keycloak and our rules accepted: the user's row, names and roles, and the tokens to keep. */
public record Login(User user, String firstName, String lastName, List<String> roles, KeycloakTokens tokens) {}
