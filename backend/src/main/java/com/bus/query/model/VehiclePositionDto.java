package com.bus.query.model;

public record VehiclePositionDto(
        String vehicleId,
        String destinationName,
        String currentLocation,
        String nextStopId,
        String nextStopName,
        Integer timeToNextStopSeconds,
        Double bearing,
        double lat,
        double lng,
        java.util.List<VehicleLegDto> travelPlan
) {
}
