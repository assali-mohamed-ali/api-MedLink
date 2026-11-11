package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.SlotDto;
import com.example.apimedlink.dto.SlotFiltersResponse;
import com.example.apimedlink.dto.SlotSearchResponse;
import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.Practitioner;
import com.example.apimedlink.model.Slot;
import com.example.apimedlink.service.SlotFilters;
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

    /** Shown when the search matches nothing at all (not on a page past the last one). */
    static final String NOTHING_FOUND = "Aucun créneau disponible. Élargissez la recherche (dates, clinique ou spécialité).";

    public static SlotSearchResponse toSearchResponse(Page<Slot> page) {
        return new SlotSearchResponse(
                page.getContent().stream().map(SlotMapper::toDto).toList(),
                page.getTotalElements(),
                page.getTotalElements() == 0 ? NOTHING_FOUND : null);
    }

    public static SlotFiltersResponse toFiltersResponse(SlotFilters filters) {
        return new SlotFiltersResponse(
                filters.clinics().stream()
                        .map(clinic -> new SlotFiltersResponse.ClinicItem(clinic.getId(), clinic.getName(), clinic.getCity()))
                        .toList(),
                filters.specialties().stream()
                        .map(specialty -> new SlotFiltersResponse.SpecialtyItem(specialty.getId(), specialty.getName()))
                        .toList(),
                filters.practitioners().stream()
                        .map(practitioner -> new SlotFiltersResponse.PractitionerItem(
                                practitioner.getId(),
                                practitioner.getFirstName(),
                                practitioner.getLastName(),
                                practitioner.getSpecialty().getId(),
                                practitioner.getClinics().stream().map(Clinic::getId).sorted().toList()))
                        .toList());
    }
}
