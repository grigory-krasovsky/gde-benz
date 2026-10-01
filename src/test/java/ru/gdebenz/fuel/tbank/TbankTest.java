package ru.gdebenz.fuel.tbank;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.gdebenz.fuel.Geo;
import ru.gdebenz.fuel.NearbyFormatter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TbankTest {

    private static final String JSON = """
            {"status":"ok","payload":[
              {"id":"a","name":"Роснефть","brand":null,"addr":"ул Айвазовского, 2А",
               "lat":55.619921,"lon":37.54283,"status":"available",
               "statusByFuelType":{"92":"not_available","95":"not_available","100":"not_available","diesel":"available"},
               "confidence":0.6738,"lastTransactionAt":"2026-10-01T11:15:44.773Z"},
              {"id":"b","name":"Газпромнефть","brand":"Газпромнефть","addr":"МКАД, 43-й километр",
               "lat":55.6318,"lon":37.466644,"status":"available",
               "statusByFuelType":{"92":"available","95":"available","100":"available","diesel":"available"},
               "confidence":0.9917,"lastTransactionAt":"2026-10-01T11:26:18.979Z"},
              {"id":"c","name":"Мосавтогаз","brand":"АВТО ГАЗ","addr":"МКАД, 44-й километр",
               "lat":55.630914,"lon":37.467478,"status":"no_data",
               "statusByFuelType":{"92":"no_data","95":"no_data"},"lastTransactionAt":null}
            ]}
            """;

    private List<TbankStation> stations() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        TbankResponse r = mapper.readValue(JSON, TbankResponse.class);
        return r.payload().stream().map(TbankClient::toStation).toList();
    }

    @Test
    void parsesAndMapsStations() throws Exception {
        List<TbankStation> st = stations();
        assertThat(st).hasSize(3);
        // brand null -> falls back to name
        assertThat(st.get(0).brand()).isEqualTo("Роснефть");
        assertThat(st.get(0).statusByFuelType()).containsEntry("diesel", "available");
        assertThat(st.get(0).confidence()).isEqualTo(0.6738);
        assertThat(st.get(0).lastTransactionAt()).isEqualTo(Instant.parse("2026-10-01T11:15:44.773Z"));
        assertThat(st.get(2).confidence()).isNull();
        assertThat(st.get(2).lastTransactionAt()).isNull();
    }

    @Test
    void matchesNearestWithinThresholdAndBrand() throws Exception {
        TbankSnapshot snap = new TbankSnapshot(stations(), 120.0);

        // ~8 m from station "a" (Роснефть)
        Optional<TbankStation> hit = snap.match(55.619990, 37.542787, "Роснефть");
        assertThat(hit).isPresent();
        assertThat(hit.get().status()).isEqualTo("available");

        // same spot but incompatible brand -> no match
        assertThat(snap.match(55.619990, 37.542787, "Лукойл")).isEmpty();

        // far away -> no match
        assertThat(snap.match(55.70, 37.60, "Роснефть")).isEmpty();
    }

    @Test
    void brandCompatibilityRules() {
        assertThat(TbankSnapshot.brandCompatible("Газпромнефть", "Газпром")).isTrue(); // contains
        assertThat(TbankSnapshot.brandCompatible("Лукойл", "ЛУКОЙЛ")).isTrue();        // case-insensitive
        assertThat(TbankSnapshot.brandCompatible("Роснефть", "")).isTrue();            // unknown -> accept
        assertThat(TbankSnapshot.brandCompatible("Лукойл", "Татнефть")).isFalse();
    }

    @Test
    void formatsTbankPopup() throws Exception {
        TbankSnapshot snap = new TbankSnapshot(stations(), 120.0);
        Clock clock = Clock.fixed(Instant.parse("2026-10-01T11:30:00Z"), ZoneOffset.UTC);
        NearbyFormatter formatter = new NearbyFormatter(clock);

        String out = formatter.formatTbankPopup(snap.match(55.6318, 37.466644, "Газпромнефть"));
        assertThat(out).contains("T-Банк");
        assertThat(out).contains("92 есть");
        assertThat(out).contains("ДТ есть");
        assertThat(out).contains("уверенность 99%");
        assertThat(out).contains("покупка");

        assertThat(formatter.formatTbankPopup(Optional.empty())).contains("нет данных");
    }

    @Test
    void geoDistanceAndBbox() {
        double d = Geo.distanceMeters(55.619990, 37.542787, 55.619921, 37.54283);
        assertThat(d).isLessThan(15.0); // ~8 m
        double[] bb = Geo.boundingBox(55.62, 37.5, 10);
        assertThat(bb[0]).isLessThan(bb[1]); // minLat < maxLat
        assertThat(bb[2]).isLessThan(bb[3]); // minLon < maxLon
    }
}
