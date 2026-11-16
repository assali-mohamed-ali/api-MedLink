package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.AgendaItemDto;
import com.example.apimedlink.dto.AgendaResponse;
import com.example.apimedlink.dto.SlotDto;
import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.Slot;
import com.example.apimedlink.model.SlotStatus;
import com.example.apimedlink.repository.BookedAppointment;
import com.example.apimedlink.service.Agenda;
import com.example.apimedlink.service.AgendaEntry;

/**
 * Agenda entities to DTOs (MLink-26). The API says {@code OPEN} where the database says {@code FREE}.
 * Runs after the transaction: it reads only what the queries loaded.
 */
public final class AgendaMapper {

    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";

    private AgendaMapper() {}

    public static AgendaResponse toResponse(Agenda agenda) {
        return new AgendaResponse(
                agenda.clinics().stream().map(AgendaMapper::toClinic).toList(),
                agenda.entries().stream().map(AgendaMapper::toItem).toList());
    }

    public static AgendaItemDto toItem(AgendaEntry entry) {
        Slot slot = entry.slot();
        BookedAppointment booked = entry.appointment();
        AgendaItemDto.AppointmentRef appointment = booked == null ? null
                : new AgendaItemDto.AppointmentRef(booked.appointmentId(), "BOOKED",
                        new AgendaItemDto.PatientRef(booked.patientId(), booked.firstName(), booked.lastName()));
        return new AgendaItemDto(slot.getId(), toApiStatus(slot.getStatus()), slot.getStartsAt(), slot.getEndsAt(),
                toClinic(slot.getClinic()), appointment);
    }

    /** A slot just created or changed: its appointment is not part of the answer. */
    public static AgendaItemDto toSlotItem(Slot slot) {
        return toItem(new AgendaEntry(slot, null));
    }

    public static String toApiStatus(SlotStatus status) {
        return status == SlotStatus.FREE ? OPEN : status.name();
    }

    private static SlotDto.ClinicRef toClinic(Clinic clinic) {
        return new SlotDto.ClinicRef(clinic.getId(), clinic.getName());
    }
}
