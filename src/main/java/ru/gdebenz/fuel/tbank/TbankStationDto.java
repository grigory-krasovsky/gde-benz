package ru.gdebenz.fuel.tbank;

import java.util.Map;

/**
 * One station from T-Bank Toplivo /api/v1/stations. Field names are already camelCase in the
 * API, so the client's ObjectMapper uses default naming. Unknown fields are ignored.
 */
public record TbankStationDto(
        String id,
        String name,
        String brand,
        String addr,
        double lat,
        double lon,
        String status,
        Map<String, String> statusByFuelType,
        Double confidence,
        String lastTransactionAt) {
}
