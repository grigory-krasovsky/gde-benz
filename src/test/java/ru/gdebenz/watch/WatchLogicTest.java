package ru.gdebenz.watch;

import org.junit.jupiter.api.Test;
import ru.gdebenz.fuel.CommentView;
import ru.gdebenz.fuel.FuelStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the favorites poller's change detection and alert rendering. */
class WatchLogicTest {

    private static CommentView report(FuelStatus status, String detail, String at) {
        return new CommentView(status, detail, Instant.parse(at), true, false);
    }

    @Test
    void normalizesGradesToCanonicalOrder() {
        assertThat(WatchLogic.normalize("ДТ, 95+, 92, дт, мойка")).isEqualTo("92,95+,ДТ");
        assertThat(WatchLogic.normalize("")).isEmpty();
        assertThat(WatchLogic.normalize(null)).isEmpty();
    }

    @Test
    void prefersFuelsNowFromSummary() {
        var e = WatchLogic.essence(FuelStatus.AVAILABLE, "95,92",
                List.of(report(FuelStatus.AVAILABLE, "ДТ", "2026-10-11T11:00:00Z")));
        assertThat(e).isEqualTo(new WatchLogic.Essence(FuelStatus.AVAILABLE, "92,95"));
    }

    @Test
    void fallsBackToNewestReportListingGrades() {
        var e = WatchLogic.essence(FuelStatus.AVAILABLE, "", List.of(
                report(FuelStatus.AVAILABLE, "", "2026-10-11T11:03:55Z"),
                report(FuelStatus.AVAILABLE, "92, ДТ · Очередь до 15 мин", "2026-10-11T07:49:02Z"),
                report(FuelStatus.AVAILABLE, "92, 95+, 100, ДТ", "2026-10-11T02:11:30Z")));
        assertThat(e.fuels()).isEqualTo("92,ДТ");
    }

    @Test
    void ignoresGradesOlderThanANegativeReport() {
        var e = WatchLogic.essence(FuelStatus.AVAILABLE, "", List.of(
                report(FuelStatus.AVAILABLE, "", "2026-10-11T11:03:55Z"),
                report(FuelStatus.UNAVAILABLE, "Перерыв", "2026-10-11T09:00:00Z"),
                report(FuelStatus.AVAILABLE, "92", "2026-10-11T08:00:00Z")));
        assertThat(e.fuels()).isEmpty();
    }

    @Test
    void closedStationHasNoGrades() {
        var e = WatchLogic.essence(FuelStatus.UNAVAILABLE, "92,95", List.of());
        assertThat(e).isEqualTo(new WatchLogic.Essence(FuelStatus.UNAVAILABLE, ""));
    }

    @Test
    void alertShowsNewAndPreviousStateWithMoscowTime() {
        String text = WatchLogic.alertText("Роснефть", "Загородное шоссе, 2Б",
                new WatchLogic.Essence(FuelStatus.UNAVAILABLE, ""),
                new WatchLogic.Essence(FuelStatus.AVAILABLE, "92,ДТ"),
                report(FuelStatus.AVAILABLE, "92,ДТ", "2026-10-11T11:03:55Z"));

        assertThat(text).contains("<b>Роснефть</b> · Загородное шоссе, 2Б");
        assertThat(text).contains("🟢 есть: 92, ДТ");
        assertThat(text).contains("было: 🔴 нет");
        assertThat(text).contains("отметка в 14:03 МСК · на месте"); // 11:03 UTC == 14:03 MSK
    }

    @Test
    void routeStartsFromCurrentLocation() {
        assertThat(WatchLogic.routeUrl(55.70, 37.60))
                .isEqualTo("https://yandex.ru/maps/?rtext=~55.700000,37.600000&rtt=auto");
    }
}
