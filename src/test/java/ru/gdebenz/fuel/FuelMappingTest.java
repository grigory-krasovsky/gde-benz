package ru.gdebenz.fuel;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import ru.gdebenz.fuel.api.NearbyResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class FuelMappingTest {

    // yes@3.4, no@0.7, queue@1.2, yes@5.1 (with svc + no meta). Deliberately out of order.
    private static final String JSON = """
            {
              "stations": [
                {"osm_id":"5169800700","brand":"Лукойл","name":"Лукойл","addr":"ул Карьер, 4",
                 "lat":55.69,"lon":37.59,"distance_km":0.7,"status":"no","detail":"",
                 "fuels_now":"","confirmations":1,"last_at":"2026-10-01 03:04:12",
                 "confidence_base":0.35,"meta":{"f":["dt"]}},
                {"osm_id":"244205852","brand":"Роснефть","name":"Роснефть","addr":"Загородное шоссе, 2Б",
                 "lat":55.70,"lon":37.60,"distance_km":3.4,"status":"yes",
                 "detail":"92, 95, 100, ДТ · Очередь 15–30 мин","fuels_now":"92,95,95+,100,ДТ",
                 "confirmations":4,"last_at":"2026-10-01 07:08:08","confidence_base":0.9},
                {"osm_id":"679426962","brand":"Роснефть","name":"Роснефть","addr":"Варшавское шоссе, 129Г",
                 "lat":55.61,"lon":37.61,"distance_km":1.2,"status":"queue",
                 "detail":"92, 95, ДТ · Очередь 30–60 мин","fuels_now":"92,95,ДТ",
                 "confirmations":2,"last_at":"2026-10-01 07:28:29","confidence_base":0.9,
                 "meta":{"f":["dt","92","95","98"]}},
                {"osm_id":"1257052128","brand":"Лукойл","name":"Лукойл","addr":"",
                 "lat":55.70,"lon":37.51,"distance_km":5.1,"status":"yes","detail":"",
                 "fuels_now":"","confirmations":1,"last_at":"2026-10-01 07:19:05",
                 "confidence_base":0.35,"meta":{"f":["dt"]},"svc":"only"}
              ],
              "updated": "2026-10-01 08:00:00"
            }
            """;

    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private NearbyResponse parse() throws Exception {
        return mapper().readValue(JSON, NearbyResponse.class);
    }

    @Test
    void parsesResponseIncludingStringIdsAndIgnoresUnknownFields() throws Exception {
        NearbyResponse response = parse();

        assertThat(response.updated()).isEqualTo("2026-10-01 08:00:00");
        assertThat(response.stations()).hasSize(4);
        assertThat(response.stations().get(0).osmId()).isEqualTo("5169800700");
        assertThat(response.stations().get(0).status()).isEqualTo("no");
        // "svc" is unknown and must be ignored; station with no meta parses meta as null.
        assertThat(response.stations().get(1).meta()).isNull();
        assertThat(response.stations().get(2).meta().f()).containsExactly("dt", "92", "95", "98");
    }

    @Test
    void mapsSortsAvailableFirstThenByDistance() throws Exception {
        NearbyResult result = FuelAvailabilityService.map(parse(), 10);

        assertThat(result.stations()).hasSize(4);
        assertThat(result.stations().get(0).status()).isEqualTo(FuelStatus.AVAILABLE);
        assertThat(result.stations().get(0).distanceKm()).isEqualTo(3.4); // nearest AVAILABLE, not nearest overall
        assertThat(result.stations().get(1).status()).isEqualTo(FuelStatus.AVAILABLE);
        assertThat(result.stations().get(2).status()).isEqualTo(FuelStatus.QUEUE);
        assertThat(result.stations().get(3).status()).isEqualTo(FuelStatus.UNAVAILABLE);
    }

    @Test
    void mapRespectsMaxResults() throws Exception {
        NearbyResult result = FuelAvailabilityService.map(parse(), 2);

        assertThat(result.stations()).hasSize(2);
        assertThat(result.stations()).allMatch(s -> s.status() == FuelStatus.AVAILABLE);
    }

    @Test
    void fuelStatusMapsKnownValuesAndFallsBack() {
        assertThat(FuelStatus.from("yes")).isEqualTo(FuelStatus.AVAILABLE);
        assertThat(FuelStatus.from("QUEUE")).isEqualTo(FuelStatus.QUEUE);
        assertThat(FuelStatus.from("no")).isEqualTo(FuelStatus.UNAVAILABLE);
        assertThat(FuelStatus.from("limit")).isEqualTo(FuelStatus.UNKNOWN);
        assertThat(FuelStatus.from(null)).isEqualTo(FuelStatus.UNKNOWN);
    }

    @Test
    void parsesLastAtAsMoscowTime() {
        Instant parsed = FuelAvailabilityService.parseLastAt("2026-10-01 07:08:08");
        // 07:08:08 MSK (+03:00) == 04:08:08 UTC
        assertThat(parsed).isEqualTo(Instant.parse("2026-10-01T04:08:08Z"));
        assertThat(FuelAvailabilityService.parseLastAt("")).isNull();
        assertThat(FuelAvailabilityService.parseLastAt(null)).isNull();
    }

    @Test
    void routeUrlIsBuiltFromCoordinates() {
        String url = NearbyFormatter.routeUrl(55.70, 37.60, 55.61, 37.61);
        assertThat(url).isEqualTo("https://yandex.ru/maps/?rtext=55.700000,37.600000~55.610000,37.610000&rtt=auto");
    }

    @Test
    void formatterRendersStatusEmojiRouteAndFreshness() throws Exception {
        NearbyResult result = FuelAvailabilityService.map(parse(), 10);
        // Fixed "now" = 2026-10-01 08:00:00 MSK (05:00:00 UTC)
        Clock clock = Clock.fixed(Instant.parse("2026-10-01T05:00:00Z"), ZoneOffset.UTC);
        NearbyFormatter formatter = new NearbyFormatter(clock);

        String out = formatter.format(result, 55.70, 37.60);

        assertThat(out).contains("🟢");          // available station
        assertThat(out).contains("🟡");          // queue station
        assertThat(out).contains("🗺 Маршрут");
        assertThat(out).contains("yandex.ru/maps/?rtext=");
        assertThat(out).contains("мин назад");
        assertThat(out).contains("<b>Роснефть</b>");
    }

    @Test
    void formatterHandlesEmptyResult() {
        NearbyFormatter formatter = new NearbyFormatter(Clock.systemUTC());
        String out = formatter.format(new NearbyResult(java.util.List.of(), "2026-10-01 08:00:00"), 55.70, 37.60);
        assertThat(out).contains("ничего не нашлось");
    }
}
