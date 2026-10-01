package ru.gdebenz.fuel.tbank;

import java.time.Instant;
import java.util.Map;

/** A T-Bank station prepared for matching/display. */
public record TbankStation(
        String brand,
        double lat,
        double lon,
        String status,
        Map<String, String> statusByFuelType,
        Double confidence,
        Instant lastTransactionAt) {
}
