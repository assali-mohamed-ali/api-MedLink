package com.example.apimedlink.dto;

/** Endpoint 5, a patient's login: {@code { "user": {…} }}. No token: those stay in the session. */
public record LoginResponse(SessionUserDto user) {}
