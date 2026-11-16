package com.example.apimedlink.service;

import com.example.apimedlink.model.Slot;
import com.example.apimedlink.repository.BookedAppointment;

/** One slot of the agenda and, when a patient holds it, that appointment ({@code null} otherwise). */
public record AgendaEntry(Slot slot, BookedAppointment appointment) {}
