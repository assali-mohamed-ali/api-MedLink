package com.example.apimedlink.dto;

import java.util.List;

/** One page of the slot search (MLink-98); {@code total} counts every match, on every page. */
public record SlotSearchResponse(List<SlotDto> items, long total) {}
