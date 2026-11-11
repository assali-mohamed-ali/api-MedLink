package com.example.apimedlink.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One page of the slot search (MLink-98); {@code total} counts every match, on every page. {@code message}
 * is there only when nothing matches at all: it suggests widening the search (MLink-100).
 */
public record SlotSearchResponse(
        List<SlotDto> items,
        long total,
        @JsonInclude(JsonInclude.Include.NON_NULL) String message) {}
