package com.example.apimedlink.service;

import com.example.apimedlink.document.PatientDocument;
import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.DeletionRequest;
import com.example.apimedlink.model.Patient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * US-16: everything MedLink holds about one patient, as plain values for the controller's mapper: the profile (with the
 * consent given at sign-up and the notification preferences), appointments, consultation reports and examination
 * results (opened), the list of documents (files downloaded one by one from their own route) and the state of the
 * deletion request.
 */
public record PatientExport(Patient patient, String email, List<Appointment> appointments,
        List<PatientReport> reports, List<PatientResult> results, List<PatientDocument> documents,
        Optional<DeletionRequest> deletionRequest, Instant deletionDueAt) {}
