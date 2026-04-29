package com.bus.query.model;

public record ArrivalPredictionDto(
        String destinationName,
        String expectedArrival,
        Integer timeToStationSeconds,
        String vehicleId,
        boolean inferred
) {
}
