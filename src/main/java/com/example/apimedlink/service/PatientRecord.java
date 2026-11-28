package com.example.apimedlink.service;

import com.example.apimedlink.model.Patient;

/**
 * What a practitioner sees of a patient (MLink-31). The consultation reports join it with MLink-32.
 * {@code canWriteReport}: a consultation with the patient has started, so a report may be written (MLink-32 follow-up).
 */
public record PatientRecord(Patient patient, boolean canWriteReport) {}
