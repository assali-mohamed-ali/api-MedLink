package com.example.apimedlink.service;

import com.example.apimedlink.model.Slot;
import com.example.apimedlink.repository.BookedAppointment;

/**
 * One slot of the agenda and, when a patient holds it, that appointment ({@code null} otherwise). A slot nobody holds
 * may carry its latest cancelled appointment (MLink-37, 37.7), {@code null} when it has none.
 */
public record AgendaEntry(Slot slot, BookedAppointment appointment, BookedAppointment cancelled) {

    public AgendaEntry(Slot slot, BookedAppointment appointment) {
        this(slot, appointment, null);
    }
}
