package ru.gdebenz.fuel.api;

import java.util.List;

/** Top-level /api/nearby response: up to ~200 stations plus the data timestamp (MSK). */
public record NearbyResponse(List<StationDto> stations, String updated) {

    public NearbyResponse {
        stations = stations == null ? List.of() : List.copyOf(stations);
    }
}
