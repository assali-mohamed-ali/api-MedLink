package com.example.apimedlink.mapper;

import com.example.apimedlink.dto.AlertDto;
import com.example.apimedlink.model.Alert;

/** Entity to API shape. Called by controllers: services never see a DTO. */
public final class AlertMapper {

    private AlertMapper() {}

    public static AlertDto toDto(Alert alert) {
        return new AlertDto(alert.getId(), alert.getType(), alert.getMessage(), alert.getAppointmentId(), alert.getCreatedAt());
    }
}
