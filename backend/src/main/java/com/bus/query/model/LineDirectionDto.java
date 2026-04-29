package com.bus.query.model;

import java.util.List;

public record LineDirectionDto(
        String id,
        String label,
        String destinationName,
        List<StopMarkerDto> stops
) {
}
