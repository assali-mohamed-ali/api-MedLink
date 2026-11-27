package com.example.apimedlink.service;

import com.example.apimedlink.document.PatientDocument;
import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.Patient;
import java.util.List;

/** US-16: everything MedLink holds about one patient, as plain values for the controller's mapper. */
public record PatientExport(Patient patient, String email, List<Appointment> appointments,
        List<PatientReport> reports, List<PatientDocument> documents) {}
