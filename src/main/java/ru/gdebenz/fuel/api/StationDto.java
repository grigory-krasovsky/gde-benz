package ru.gdebenz.fuel.api;

/**
 * One station as returned by /api/nearby. Field names map from snake_case via the client's
 * ObjectMapper. Unknown fields (e.g. {@code svc}) are ignored.
 */
public record StationDto(
        String osmId,
        String brand,
        String name,
        String addr,
        double lat,
        double lon,
        double distanceKm,
        String status,
        String detail,
        String fuelsNow,
        int confirmations,
        String lastAt,
        double confidenceBase,
        StationMetaDto meta) {
}
