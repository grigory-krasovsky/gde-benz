package ru.gdebenz.fuel.tbank;

import java.util.List;

/** Top-level T-Bank Toplivo response: {@code status} + {@code payload} (stations in the bbox). */
public record TbankResponse(String status, List<TbankStationDto> payload) {

    public TbankResponse {
        payload = payload == null ? List.of() : List.copyOf(payload);
    }
}
