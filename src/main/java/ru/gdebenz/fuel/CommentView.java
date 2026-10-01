package ru.gdebenz.fuel;

import java.time.Instant;

/** A driver report prepared for display: status mapped, timestamp parsed, trust flattened. */
public record CommentView(
        FuelStatus status,
        String detail,
        Instant createdAt,
        boolean onSite,
        boolean reliable) {
}
