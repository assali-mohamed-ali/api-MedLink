package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.SlotDto;
import com.example.apimedlink.dto.SlotSearchResponse;
import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.Practitioner;
import com.example.apimedlink.model.Slot;
import org.springframework.data.domain.Page;

/** Entity to API shape. Called by controllers: services never see a DTO. */
public final class SlotMapper {

    private SlotMapper() {}

    public static SlotDto toDto(Slot slot) {
        Clinic clinic = slot.getClinic();
        Practitioner practitioner = slot.getPractitioner();
        return new SlotDto(
                slot.getId(),
                slot.getStartsAt(),
                slot.getEndsAt(),
                new SlotDto.ClinicRef(clinic.getId(), clinic.getName()),
                new SlotDto.PractitionerRef(practitioner.getId(), practitioner.getFirstName(), practitioner.getLastName()),
                practitioner.getSpecialty().getName());
    }

    public static SlotSearchResponse toSearchResponse(Page<Slot> page) {
        return new SlotSearchResponse(page.getContent().stream().map(SlotMapper::toDto).toList(), page.getTotalElements());
    }
}
