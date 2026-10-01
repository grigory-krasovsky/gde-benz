package ru.gdebenz.fuel;

/** Availability status of a station, mapped from the raw {@code status} field. */
public enum FuelStatus {

    AVAILABLE("🟢"),
    QUEUE("🟡"),
    UNAVAILABLE("🔴"),
    UNKNOWN("❔");

    private final String emoji;

    FuelStatus(String emoji) {
        this.emoji = emoji;
    }

    public String emoji() {
        return emoji;
    }

    public static FuelStatus from(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        return switch (raw.trim().toLowerCase()) {
            case "yes" -> AVAILABLE;
            case "queue" -> QUEUE;
            case "no" -> UNAVAILABLE;
            default -> UNKNOWN;
        };
    }

    /** Sort rank: available first, closed last. */
    public int rank() {
        return switch (this) {
            case AVAILABLE -> 0;
            case QUEUE -> 1;
            case UNKNOWN -> 2;
            case UNAVAILABLE -> 3;
        };
    }
}
