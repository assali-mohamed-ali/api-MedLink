package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.ReassignmentDto;
import com.example.apimedlink.service.ReassignmentService;
import java.util.function.Function;
import org.springframework.data.domain.Page;

public final class ReassignmentMapper {
    private ReassignmentMapper() {}

    public static ReassignmentDto.SlotRef slot(ReassignmentService.SlotSummary s) {
        return new ReassignmentDto.SlotRef(s.id(), s.clinicId(), s.clinicName(), s.practitionerId(),
                s.firstName(), s.lastName(), s.startsAt(), s.endsAt());
    }

    public static ReassignmentDto proposal(ReassignmentService.Proposal p) {
        return new ReassignmentDto(p.id(), p.appointmentId(), slot(p.original()), slot(p.target()),
                p.status().name(), p.patientAccepted(), p.practitionerAccepted());
    }

    public static ReassignmentDto.Candidate candidate(ReassignmentService.Candidate c) {
        return new ReassignmentDto.Candidate(c.appointmentId(), c.patientFirstName(), c.patientLastName(), slot(c.original()));
    }

    public static <T, R> ReassignmentDto.Page<R> page(Page<T> result, Function<T, R> mapper) {
        return new ReassignmentDto.Page<>(result.getContent().stream().map(mapper).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }
}
