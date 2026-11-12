package com.example.apimedlink.dto;

import java.util.List;

/** One page of a patient's own appointments (MLink-108); {@code total} counts them all, on every page. */
public record AppointmentPageResponse(List<AppointmentDto> items, long total) {}
