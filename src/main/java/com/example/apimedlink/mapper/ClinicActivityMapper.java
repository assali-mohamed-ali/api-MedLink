package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.AdminPractitionerDto;
import com.example.apimedlink.dto.SlotDto;
import com.example.apimedlink.model.Clinic;
import com.example.apimedlink.model.Practitioner;
import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** MLink-35: the practitioners staff supervise. */
public final class ClinicActivityMapper {

    private ClinicActivityMapper() {}

    public static List<AdminPractitionerDto> toDtos(List<Practitioner> practitioners) {
        Collator french = Collator.getInstance(Locale.FRENCH);
        return practitioners.stream().map(practitioner -> new AdminPractitionerDto(
                practitioner.getId(),
                practitioner.getFirstName(),
                practitioner.getLastName(),
                new AdminPractitionerDto.SpecialtyRef(practitioner.getSpecialty().getId(),
                        practitioner.getSpecialty().getName()),
                practitioner.getClinics().stream()
                        .sorted(Comparator.comparing(Clinic::getName, french))
                        .map(clinic -> new SlotDto.ClinicRef(clinic.getId(), clinic.getName()))
                        .toList()))
                .toList();
    }
}
