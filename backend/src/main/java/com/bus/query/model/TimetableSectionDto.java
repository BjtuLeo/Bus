package com.bus.query.model;

import java.util.List;

public record TimetableSectionDto(
        String title,
        String firstDeparture,
        String lastDeparture,
        List<String> frequencies,
        List<String> departures
) {
}
