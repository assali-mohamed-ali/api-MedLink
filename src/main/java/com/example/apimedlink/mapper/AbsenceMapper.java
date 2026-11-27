package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.AbsenceDecisionDto;
import com.example.apimedlink.dto.AbsenceDto;
import com.example.apimedlink.dto.DecideAbsenceAppointmentRequest;
import com.example.apimedlink.model.Absence;
import com.example.apimedlink.service.AbsenceAppointment;
import com.example.apimedlink.service.AbsenceDecision;
import com.example.apimedlink.service.DeclaredAbsence;
import java.util.List;

/** US-21: entities and plain values to API shape and back. Called by the controller: services never see a DTO. */
public final class AbsenceMapper {

    private AbsenceMapper() {}

    public static AbsenceDto toDto(Absence absence) {
        return new AbsenceDto(absence.getId(), absence.getStartsAt(), absence.getEndsAt(), absence.getReason(), null, null);
    }

    public static AbsenceDto toDto(DeclaredAbsence declared) {
        Absence absence = declared.absence();
        return new AbsenceDto(absence.getId(), absence.getStartsAt(), absence.getEndsAt(), absence.getReason(),
                declared.closedFreeSlots(), toDtos(declared.toDecide()));
    }

    public static List<AbsenceDto.ToDecide> toDtos(List<AbsenceAppointment> appointments) {
        return appointments.stream().map(item -> new AbsenceDto.ToDecide(
                item.appointment().getId(),
                item.appointment().getSlot().getStartsAt(),
                item.appointment().getSlot().getEndsAt(),
                item.appointment().getSlot().getClinic().getName(),
                item.patient() == null ? null : item.patient().getFirstName(),
                item.patient() == null ? null : item.patient().getLastName())).toList();
    }

    public static AbsenceDecisionDto toDto(AbsenceDecision decision) {
        return new AbsenceDecisionDto(decision.action().name(), decision.appointmentId(),
                decision.moved() == null ? null : decision.moved().getId(),
                decision.moved() == null ? null : decision.moved().getSlot().getStartsAt());
    }

    public static AbsenceDecision.Action toAction(DecideAbsenceAppointmentRequest.Action action) {
        return action == DecideAbsenceAppointmentRequest.Action.CANCEL
                ? AbsenceDecision.Action.CANCEL : AbsenceDecision.Action.POSTPONE;
    }
}
