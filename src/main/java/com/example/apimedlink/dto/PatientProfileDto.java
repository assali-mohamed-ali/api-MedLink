package com.example.apimedlink.dto;

/** US-15: the part of a patient's profile the patient may read and change. Names and email live in Keycloak. */
public record PatientProfileDto(String phone, String address) {}
