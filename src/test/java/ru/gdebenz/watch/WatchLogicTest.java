package ru.gdebenz.watch;

import org.junit.jupiter.api.Test;
import ru.gdebenz.fuel.CommentView;
import ru.gdebenz.fuel.FuelStatus;
import ru.gdebenz.fuel.api.ChatMessageDto;

import java.time.Instant;
import java.util.List;
import java.util.Set;

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
    void alertListsChangesCurrentSetAndMoscowTime() {
        var now = new WatchLogic.Essence(FuelStatus.AVAILABLE, "92,ДТ");
        String text = WatchLogic.alertText("Роснефть", "Загородное шоссе, 2Б",
                List.of("🔴 Закончился 95", "🟢 Появился 92"), now,
                report(FuelStatus.AVAILABLE, "92,ДТ", "2026-10-11T11:03:55Z"));

        assertThat(text).contains("<b>Роснефть</b> · Загородное шоссе, 2Б");
        assertThat(text).contains("\n🔴 Закончился 95\n🟢 Появился 92\n");
        assertThat(text).contains("Сейчас есть: 92, ДТ");
        assertThat(text).contains("отметка в 14:03 МСК · на месте"); // 11:03 UTC == 14:03 MSK
    }

    private static WatchLogic.Essence open(String fuels) {
        return new WatchLogic.Essence(FuelStatus.AVAILABLE, fuels);
    }

    @Test
    void reportsGoneAndAppearedGradesWithPlurals() {
        assertThat(WatchLogic.changeLines(open("92,95,ДТ"), open("92,98,100"), Set.of()))
                .containsExactly("🔴 Закончились 95, ДТ", "🟢 Появились 98, 100");
        assertThat(WatchLogic.changeLines(open("92,95"), open("92"), Set.of()))
                .containsExactly("🔴 Закончился 95");
    }

    @Test
    void filterNarrowsChangesAndCovers95Plus() {
        Set<String> filter = Set.of("95");
        assertThat(WatchLogic.changeLines(open("92,95,ДТ"), open("92,95"), filter)).isEmpty(); // only ДТ changed
        assertThat(WatchLogic.changeLines(open("92"), open("92,95+,ДТ"), filter))
                .containsExactly("🟢 Появился 95+");
    }

    @Test
    void closingReportsWatchedGradesOnly() {
        var closed = new WatchLogic.Essence(FuelStatus.UNAVAILABLE, "");
        assertThat(WatchLogic.changeLines(open("92,95"), closed, Set.of()))
                .containsExactly("🔴 Топливо закончилось", "Закончились 92, 95");
        assertThat(WatchLogic.changeLines(open("ДТ"), closed, Set.of("95"))).isEmpty();
        assertThat(WatchLogic.changeLines(open(""), closed, Set.of("95")))
                .containsExactly("🔴 Топливо закончилось"); // grades were unknown: may concern the user
    }

    @Test
    void openingWithoutGradesAndQueueChanges() {
        var closed = new WatchLogic.Essence(FuelStatus.UNAVAILABLE, "");
        assertThat(WatchLogic.changeLines(closed, open(""), Set.of()))
                .containsExactly("🟢 Топливо появилось (марки не указаны)");

        var queue = new WatchLogic.Essence(FuelStatus.QUEUE, "92,95");
        assertThat(WatchLogic.changeLines(open("92,95"), queue, Set.of())).containsExactly("🟡 Появилась очередь");
        assertThat(WatchLogic.changeLines(queue, open("92,95"), Set.of("95"))).containsExactly("🟢 Очередь пропала");
        assertThat(WatchLogic.changeLines(open("92"), new WatchLogic.Essence(FuelStatus.QUEUE, "92"), Set.of("95")))
                .isEmpty();
    }

    private static final List<String> KEYWORDS = List.of("бензовоз", "привез", "завоз", "слив");

    @Test
    void keywordStatementsMatchButQuestionsDoNot() {
        assertThat(WatchLogic.hasKeywordStatement("Бензовоз приехал, сливают 95", KEYWORDS)).isTrue();
        assertThat(WatchLogic.hasKeywordStatement("Бензовоз сегодня был?", KEYWORDS)).isFalse();
        assertThat(WatchLogic.hasKeywordStatement("Привезли 92! А 100 будет?", KEYWORDS)).isTrue();
        assertThat(WatchLogic.hasKeywordStatement("Когда завоз?", KEYWORDS)).isFalse();
        assertThat(WatchLogic.hasKeywordStatement("100 есть?", KEYWORDS)).isFalse();
        assertThat(WatchLogic.hasKeywordStatement("Всем привет\nпривёз бензовоз 95", KEYWORDS)).isTrue(); // ё folded
        assertThat(WatchLogic.hasKeywordStatement("бензовоз когда будет", KEYWORDS)).isTrue(); // accepted defect
        assertThat(WatchLogic.hasKeywordStatement("", KEYWORDS)).isFalse();
    }

    @Test
    void chatAlertQuotesMessageWithAuthorAndMoscowTime() {
        String text = WatchLogic.chatAlertText("Роснефть", "Загородное шоссе, 2Б",
                new ChatMessageDto(1L, "Николай Б.", "Бензовоз <сливает> 95", "2026-10-11T18:19:10.184919Z"));
        assertThat(text).startsWith("🚚 <b>Роснефть</b> · Загородное шоссе, 2Б");
        assertThat(text).contains("«Бензовоз &lt;сливает&gt; 95»");
        assertThat(text).contains("— Николай Б., 21:19 МСК");
    }

    @Test
    void routeStartsFromCurrentLocation() {
        assertThat(WatchLogic.routeUrl(55.70, 37.60))
                .isEqualTo("https://yandex.ru/maps/?rtext=~55.700000,37.600000&rtt=auto");
    }
}
