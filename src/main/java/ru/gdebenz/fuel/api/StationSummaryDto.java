package ru.gdebenz.fuel.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Per-station summary from /api/comments/{osm_id}. Unlike /api/nearby this endpoint uses camelCase,
 * hence the explicit {@link JsonProperty}. {@code updated} is the time of the latest report (UTC);
 * {@code fuelsNow} is often empty even when the latest report lists grades.
 */
public record StationSummaryDto(
        String status,
        String updated,
        @JsonProperty("fuelsNow") String fuelsNow,
        int confirmations,
        String brand,
        String addr,
        double lat,
        double lon) {
}
