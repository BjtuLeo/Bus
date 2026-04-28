package com.bus.query.model;

import java.util.List;

public record StopMarkerDto(
        String id,
        String name,
        double lat,
        double lng,
        List<ArrivalPredictionDto> arrivals
) {
}
