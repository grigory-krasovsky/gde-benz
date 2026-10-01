package ru.gdebenz.fuel;

import java.util.List;

/** Sorted, display-ready stations plus the source data timestamp. */
public record NearbyResult(List<StationView> stations, String updated) {
}
