package ru.gdebenz.fuel;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;
import ru.gdebenz.fuel.api.NearbyResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.gdebenz.fuel.api.CommentDto;
import ru.gdebenz.fuel.tbank.TbankStation;

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
    void parsesTimestampAsMoscowTime() {
        Instant parsed = FuelAvailabilityService.parseTimestamp("2026-10-01 07:08:08");
        // 07:08:08 MSK (+03:00) == 04:08:08 UTC
        assertThat(parsed).isEqualTo(Instant.parse("2026-10-01T04:08:08Z"));
        assertThat(FuelAvailabilityService.parseTimestamp("")).isNull();
        assertThat(FuelAvailabilityService.parseTimestamp(null)).isNull();
    }

    @Test
    void fuelCellIsDashWhenGradeAbsent() {
        StationView s = station("92,ДТ", 0.9);
        assertThat(NearbyFormatter.fuelCell(s, "95", Optional.empty())).isEqualTo("—");
    }

    @Test
    void fuelCellShowsGdebenzConfidenceWhenNoTbank() {
        StationView s = station("92,95", 0.9);
        assertThat(NearbyFormatter.fuelCell(s, "95", Optional.empty())).isEqualTo("90%");
    }

    @Test
    void tbankConfirmationRaisesConfidenceTowardHundred() {
        StationView s = station("92,95", 0.9);
        Optional<TbankStation> tb = Optional.of(tbank(Map.of("95", "available"), 0.9));
        assertThat(NearbyFormatter.fuelCell(s, "95", tb)).isEqualTo("95%");
    }

    @Test
    void tbankNegativeNeverLowersConfidence() {
        StationView s = station("92,95", 0.9);
        Optional<TbankStation> tb = Optional.of(tbank(Map.of("95", "not_available"), 0.9));
        assertThat(NearbyFormatter.fuelCell(s, "95", tb)).isEqualTo("90%");
    }

    @Test
    void blendedConfidenceIsCappedAt99() {
        Optional<TbankStation> tb = Optional.of(tbank(Map.of("95", "available"), 1.0));
        assertThat(NearbyFormatter.blendedConfidencePercent(0.98, "95", tb)).isLessThanOrEqualTo(99);
    }

    private static StationView station(String fuelsNow, double confidence) {
        return new StationView("1", "Роснефть", "", 55.7, 37.6, 1.0,
                FuelStatus.AVAILABLE, "", fuelsNow, 1, null, confidence);
    }

    private static TbankStation tbank(Map<String, String> byFuel, double confidence) {
        return new TbankStation("Роснефть", 55.7, 37.6, "available", byFuel, confidence, null);
    }

    @Test
    void hasFuelColumnMerges95Plus() {
        assertThat(NearbyFormatter.hasFuelColumn("95+", "95")).isTrue();   // 95+ counts as 95
        assertThat(NearbyFormatter.hasFuelColumn("95", "95")).isTrue();
        assertThat(NearbyFormatter.hasFuelColumn("92,ДТ", "95")).isFalse();
        assertThat(NearbyFormatter.hasFuelColumn("92", "92")).isTrue();
        assertThat(NearbyFormatter.hasFuelColumn("95+", "98")).isFalse();
    }

    @Test
    void hasFuelMatchesExactGradeTokens() {
        assertThat(NearbyFormatter.hasFuel("92,95,95+,100,ДТ", "95")).isTrue();
        assertThat(NearbyFormatter.hasFuel("92,95,95+,100,ДТ", "95+")).isTrue();
        assertThat(NearbyFormatter.hasFuel("92,95,95+,100,ДТ", "98")).isFalse();
        assertThat(NearbyFormatter.hasFuel("95+", "95")).isFalse(); // exact token, no substring match
        assertThat(NearbyFormatter.hasFuel("", "92")).isFalse();
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
        assertThat(out).contains("⏳");          // queue extracted from detail onto the headline
        assertThat(out).contains("<blockquote expandable>"); // collapsible secondary details
        assertThat(out).contains("Загородное шоссе, 2Б");     // address lives in the blockquote
        assertThat(out).contains("мин назад");
        assertThat(out).contains("<b>Роснефть</b>");
        assertThat(out).doesNotContain("данные на");          // data timestamp removed
        assertThat(out).doesNotContain("Маршрут");            // route is a URL button now, not text
    }

    @Test
    void formatterHandlesEmptyResult() {
        NearbyFormatter formatter = new NearbyFormatter(Clock.systemUTC());
        String out = formatter.format(new NearbyResult(List.of(), "2026-10-01 08:00:00"), 55.70, 37.60);
        assertThat(out).contains("ничего не нашлось");
    }

    private static final String COMMENTS_JSON = """
            [
              {"status":"yes","detail":"92,95","created_at":"2026-10-01 07:12:52","edited":false,"on_site":true,"svc":true},
              {"status":"yes","detail":"95, ДТ · Очередь до 15 мин","created_at":"2026-10-01 05:21:58","edited":true,"author_reliable":false,"author_tier":0,"acct_ok":true},
              {"status":"no","detail":"Перерыв","created_at":"2026-09-30 17:56:11","edited":false,"on_site":true},
              {"status":"yes","detail":"92, 95, ДТ · Очередь 15–30 мин","created_at":"2026-09-30 14:54:57","edited":false,"author_reliable":true,"author_tier":1}
            ]
            """;

    private List<CommentDto> parseComments() throws Exception {
        return List.of(mapper().readValue(COMMENTS_JSON, CommentDto[].class));
    }

    @Test
    void parsesCommentsIncludingTrustFlags() throws Exception {
        List<CommentDto> comments = parseComments();
        assertThat(comments).hasSize(4);
        assertThat(comments.get(0).onSite()).isTrue();
        assertThat(comments.get(0).svc()).isTrue();
        assertThat(comments.get(1).edited()).isTrue();
        assertThat(comments.get(3).authorReliable()).isTrue();
        assertThat(comments.get(3).authorTier()).isEqualTo(1);
    }

    @Test
    void mapsCommentsToViews() throws Exception {
        List<CommentView> views = FuelAvailabilityService.mapComments(parseComments());

        assertThat(views).hasSize(4);
        assertThat(views.get(0).onSite()).isTrue();
        assertThat(views.get(2).status()).isEqualTo(FuelStatus.UNAVAILABLE);
        assertThat(views.get(3).reliable()).isTrue();   // author_tier 1 implies reliable
        assertThat(views.get(0).createdAt()).isNotNull();
    }

    @Test
    void filtersCommentsOlderThanMaxAge() throws Exception {
        List<CommentView> views = FuelAvailabilityService.mapComments(parseComments());
        Instant now = Instant.parse("2026-10-01T05:00:00Z"); // 08:00 MSK
        var recent = FuelAvailabilityService.filterRecent(views, now, java.time.Duration.ofHours(4));
        // Only the two reports within the last 4h (07:12 and 05:21 MSK) survive.
        assertThat(recent).hasSize(2);
    }

    @Test
    void formatsCommentsWithMarkers() throws Exception {
        List<CommentView> views = FuelAvailabilityService.mapComments(parseComments());
        Clock clock = Clock.fixed(Instant.parse("2026-10-01T05:00:00Z"), ZoneOffset.UTC);

        String out = new NearbyFormatter(clock).formatComments(views);

        assertThat(out).contains("💬");
        assertThat(out).contains("на месте");
        assertThat(out).contains("✓");          // reliable marker
        assertThat(out).contains("Перерыв");
        assertThat(out).contains("🔴");          // the "no" report
    }
}
