package ru.gdebenz.watch;

import ru.gdebenz.fuel.CommentView;
import ru.gdebenz.fuel.FuelStatus;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure helpers for the favorites poller: what a station's "essence" is (status + grade set),
 * and how an alert about its change is rendered. Kept static for testability.
 */
final class WatchLogic {

    /** Users see times in MSK. */
    static final ZoneOffset MSK = ZoneOffset.ofHours(3);

    /** Recognized grades, in display order; anything else in a report is ignored. */
    private static final List<String> GRADES = List.of("80", "92", "95", "95+", "98", "100", "ДТ");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private WatchLogic() {
    }

    /** Current alert-relevant state of a station. {@code fuels} is a normalized CSV ("" if none/unknown). */
    record Essence(FuelStatus status, String fuels) {
    }

    /**
     * The grade set available now: gdebenz's {@code fuelsNow} when present, otherwise the newest
     * non-negative report that lists grades ({@code fuelsNow} is often empty in the summary).
     * A closed station has no grades.
     */
    static Essence essence(FuelStatus status, String fuelsNow, List<CommentView> newestFirst) {
        if (status == FuelStatus.UNAVAILABLE) {
            return new Essence(status, "");
        }
        String fuels = normalize(fuelsNow);
        if (fuels.isEmpty()) {
            for (CommentView c : newestFirst) {
                if (c.status() == FuelStatus.UNAVAILABLE) {
                    break; // a later "no" makes older grade lists stale
                }
                fuels = normalize(firstSegment(c.detail()));
                if (!fuels.isEmpty()) {
                    break;
                }
            }
        }
        return new Essence(status, fuels);
    }

    /** Canonical CSV of the known grades found in {@code text} (e.g. "92, дт, 95+" -> "92,95+,ДТ"). */
    static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Set<String> found = new LinkedHashSet<>();
        for (String token : text.split(",")) {
            found.add(token.trim().toUpperCase(Locale.ROOT));
        }
        List<String> out = new ArrayList<>();
        for (String grade : GRADES) {
            if (found.contains(grade.toUpperCase(Locale.ROOT))) {
                out.add(grade);
            }
        }
        return String.join(",", out);
    }

    /** Report details look like "92, 95 · Очередь до 15 мин"; grades are the first segment. */
    private static String firstSegment(String detail) {
        if (detail == null) {
            return "";
        }
        int dot = detail.indexOf('·');
        return dot >= 0 ? detail.substring(0, dot) : detail;
    }

    /** HTML alert: station, new state, previous state, and the latest report's time (MSK) and details. */
    static String alertText(String brand, String addr, Essence was, Essence now, CommentView latest) {
        StringBuilder sb = new StringBuilder("🔔 <b>").append(esc(brand == null || brand.isBlank() ? "АЗС" : brand))
                .append("</b>");
        if (addr != null && !addr.isBlank()) {
            sb.append(" · ").append(esc(addr));
        }
        sb.append('\n').append(describe(now));
        if (now.status() == FuelStatus.UNAVAILABLE && latest != null && !latest.detail().isBlank()) {
            sb.append(" · ").append(esc(latest.detail()));
        }
        sb.append("\nбыло: ").append(describe(was));
        if (latest != null && latest.createdAt() != null) {
            sb.append("\nотметка в ").append(timeMsk(latest.createdAt())).append(" МСК");
            if (latest.onSite()) {
                sb.append(" · на месте");
            }
        }
        return sb.toString();
    }

    static String describe(Essence e) {
        String fuels = e.fuels().isEmpty() ? "" : ": " + e.fuels().replace(",", ", ");
        return e.status().emoji() + " " + switch (e.status()) {
            case AVAILABLE -> "есть" + fuels;
            case QUEUE -> "очередь" + fuels;
            case UNAVAILABLE -> "нет";
            case UNKNOWN -> "статус неизвестен";
        };
    }

    static String timeMsk(Instant at) {
        return HH_MM.format(at.atOffset(MSK));
    }

    static int hourMsk(Instant at) {
        return at.atOffset(MSK).getHour();
    }

    /** Yandex Maps route to the station from wherever the user is (empty origin = current location). */
    static String routeUrl(double lat, double lon) {
        return String.format(Locale.ROOT, "https://yandex.ru/maps/?rtext=~%.6f,%.6f&rtt=auto", lat, lon);
    }

    private static String esc(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
