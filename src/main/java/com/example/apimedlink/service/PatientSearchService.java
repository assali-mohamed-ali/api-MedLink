package com.example.apimedlink.service;

import com.example.apimedlink.model.Patient;
import com.example.apimedlink.repository.PatientRepository;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-22: a practitioner finds the patients linked to them (a BOOKED appointment, a report they wrote or a document they
 * uploaded) by part of a first or last name. Never returns a patient the practitioner is not linked to.
 */
@Service
public class PatientSearchService {

    /** At most this many results: the practitioner narrows the name instead of paging. */
    public static final int LIMIT = 50;

    private final PatientRepository patients;

    public PatientSearchService(PatientRepository patients) {
        this.patients = patients;
    }

    @Transactional(readOnly = true)
    public List<Patient> search(UUID practitionerId, String query) {
        String text = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return patients.searchLinked(practitionerId, "%" + escaped + "%", PageRequest.of(0, LIMIT));
    }
}
