package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.DecideDeletionRequest;
import com.example.apimedlink.dto.DeletionRequestDto;
import com.example.apimedlink.dto.DeletionReviewDto;
import com.example.apimedlink.model.DeletionRequest;
import com.example.apimedlink.model.DeletionStatus;
import com.example.apimedlink.service.DeletionReview;
import java.time.Instant;
import java.util.Optional;

/** US-16: entities and plain values to API shape and back. Called by controllers: services never see a DTO. */
public final class DeletionRequestMapper {

    private DeletionRequestMapper() {}

    /** The patient's view; {@code dueAt} is the date computed by the service for the request, null without one. */
    public static DeletionRequestDto toDto(Optional<DeletionRequest> request, Instant dueAt) {
        return request
                .map(found -> new DeletionRequestDto(true, found.getStatus().name(), found.getRequestedAt(), dueAt,
                        found.getDecisionNote()))
                .orElseGet(() -> new DeletionRequestDto(false, null, null, null, null));
    }

    public static DeletionReviewDto toDto(DeletionReview review) {
        DeletionRequest request = review.request();
        return new DeletionReviewDto(review.patient().getId(), review.patient().getFirstName(),
                review.patient().getLastName(), request.getStatus().name(), request.getRequestedAt(), review.dueAt(),
                review.lastConsultationAt(), review.retainedUntil(), request.getDecisionNote());
    }

    public static DeletionStatus toStatus(DecideDeletionRequest.Decision decision) {
        return decision == DecideDeletionRequest.Decision.APPROVED ? DeletionStatus.APPROVED : DeletionStatus.REJECTED;
    }
}
