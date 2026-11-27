package com.example.apimedlink.service;

import com.example.apimedlink.model.Patient;
import com.example.apimedlink.model.RecordNumbers;
import com.example.apimedlink.repository.PatientRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * US-22: a practitioner finds the patients linked to them (a BOOKED appointment, a report they wrote or a document they
 * uploaded) by part of a name, a full name, or a record number ({@code MED-000042}, {@code med42}, {@code 000042}).
 * Never returns a patient the practitioner is not linked to, whatever the text: a stranger's record number finds
 * nothing. The link rule is the record's ({@link PatientRepository#isLinkedTo}), so every result can be opened.
 */
@Service
public class PatientSearchService {

    private final PatientRepository patients;

    public PatientSearchService(PatientRepository patients) {
        this.patients = patients;
    }

    /** One page by name; {@code page} from 0, {@code size} already bounded by the controller. */
    @Transactional(readOnly = true)
    public Page<Patient> search(UUID practitionerId, String query, int page, int size) {
        String text = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return patients.searchLinked(practitionerId, "%" + escaped + "%", RecordNumbers.parse(text),
                PageRequest.of(page, size));
    }
}
