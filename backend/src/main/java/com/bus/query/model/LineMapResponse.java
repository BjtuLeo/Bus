package com.bus.query.model;

import java.util.List;

public record LineMapResponse(
        String id,
        String name,
        String displayName,
        String city,
        List<LatLngDto> routePath,
        List<StopMarkerDto> stops,
        List<VehiclePositionDto> vehicles,
        String vehicleStatus,
        String vehicleMessage
) {
}
