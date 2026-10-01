package ru.gdebenz.fuel;

import java.time.Instant;

/** A station prepared for display: raw status mapped, timestamp parsed. */
public record StationView(
        String osmId,
        String brand,
        String addr,
        double lat,
        double lon,
        double distanceKm,
        FuelStatus status,
        String detail,
        String fuelsNow,
        int confirmations,
        Instant lastAt) {
}
