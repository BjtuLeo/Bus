package com.bus.query.model;

public record VehicleLegDto(
        String fromStopId,
        String fromStopName,
        String toStopId,
        String toStopName,
        Integer durationSeconds
) {
}
