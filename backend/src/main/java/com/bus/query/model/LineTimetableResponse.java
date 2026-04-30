package com.bus.query.model;

import java.util.List;

public record LineTimetableResponse(
        String lineId,
        String lineName,
        String fromStopPointId,
        String fromStopPointName,
        List<TimetableSectionDto> sections
) {
}
