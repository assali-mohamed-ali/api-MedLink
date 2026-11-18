package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.AppointmentDto;
import com.example.apimedlink.dto.AppointmentPageResponse;
import com.example.apimedlink.dto.SlotDto;
import com.example.apimedlink.model.Appointment;
import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.Practitioner;
import com.example.apimedlink.model.Slot;
import org.springframework.data.domain.Page;

/** Entity to API shape. Called by controllers: services never see a DTO. */
public final class AppointmentMapper {

    private AppointmentMapper() {}

    public static AppointmentDto toDto(Appointment appointment) {
        Slot slot = appointment.getSlot();
        Clinic clinic = slot.getClinic();
        Practitioner practitioner = slot.getPractitioner();
        return new AppointmentDto(
                appointment.getId(),
                appointment.getStatus().name(),
                new SlotDto.ClinicRef(clinic.getId(), clinic.getName()),
                new SlotDto.PractitionerRef(practitioner.getId(), practitioner.getFirstName(), practitioner.getLastName()),
                slot.getStartsAt(),
                slot.getEndsAt());
    }

    public static AppointmentPageResponse toPageResponse(Page<Appointment> page) {
        return new AppointmentPageResponse(
                page.getContent().stream().map(AppointmentMapper::toDto).toList(), page.getTotalElements());
    }
}
