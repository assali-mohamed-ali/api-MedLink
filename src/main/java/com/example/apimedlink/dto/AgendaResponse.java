package com.example.apimedlink.dto;

import java.util.List;

/**
 * A practitioner's agenda (MLink-26): the clinics they work in (the legend, the filter and the open-slot form
 * need them even in a week without a slot) and their slots, earliest first.
 */
public record AgendaResponse(List<SlotDto.ClinicRef> clinics, List<AgendaItemDto> items) {}
