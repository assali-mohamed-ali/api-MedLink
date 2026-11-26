package com.example.apimedlink.service;

import com.example.apimedlink.exception.ApiException;
import com.example.apimedlink.model.Practitioner;
import com.example.apimedlink.repository.PractitionerRepository;
import java.text.Collator;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MLink-35: what staff read to supervise the clinics, never change: the practitioners and any one's agenda. The
 * agenda is the practitioner's own (AgendaService), so staff see exactly what the practitioner sees, minus the actions.
 */
@Service
public class ClinicActivityService {

    private final PractitionerRepository practitioners;
    private final AgendaService agendas;

    public ClinicActivityService(PractitionerRepository practitioners, AgendaService agendas) {
        this.practitioners = practitioners;
        this.agendas = agendas;
    }

    /** Every practitioner with their specialty and clinics, by last then first name in French order. */
    @Transactional(readOnly = true)
    public List<Practitioner> practitioners() {
        Collator french = Collator.getInstance(Locale.FRENCH);
        return practitioners.findAllBy().stream()
                .sorted(Comparator.comparing(Practitioner::getLastName, french)
                        .thenComparing(Practitioner::getFirstName, french))
                .toList();
    }

    /** One practitioner's clinics and slots starting in {@code [from, to)}; 404 for an id that is no practitioner. */
    @Transactional(readOnly = true)
    public Agenda agenda(UUID practitionerId, Instant from, Instant to) {
        if (!practitioners.existsById(practitionerId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PRACTITIONER_NOT_FOUND", "Ce praticien n'existe pas.");
        }
        return agendas.agenda(practitionerId, from, to);
    }
}
