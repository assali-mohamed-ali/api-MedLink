package com.example.apimedlink.service;

import com.example.apimedlink.model.Patient;

/** What a practitioner sees of a patient (MLink-31). The consultation reports join it with MLink-32. */
public record PatientRecord(Patient patient) {}
