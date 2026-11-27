package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.DeletionRequestDto;
import com.example.apimedlink.model.DeletionRequest;
import java.time.Instant;
import java.util.Optional;

/** US-16: entity to API shape. Called by the controller: services never see a DTO. */
public final class DeletionRequestMapper {

    private DeletionRequestMapper() {}

    public static DeletionRequestDto toDto(Optional<DeletionRequest> request, Instant scheduledFor) {
        return request
                .map(found -> new DeletionRequestDto(true, found.getRequestedAt(), scheduledFor))
                .orElseGet(() -> new DeletionRequestDto(false, null, null));
    }
}
