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

    /**
     * Human-readable change lines ("🔴 Закончился 95", "🟢 Появились 92, ДТ", queue on/off), narrowed to
     * {@code filter} grades when it is non-empty ("95" also covers "95+"). Empty = nothing relevant
     * changed for this user, so no alert.
     */
    static List<String> changeLines(Essence was, Essence now, Set<String> filter) {
        if (now.status() == FuelStatus.UNKNOWN) {
            return List.of();
        }
        List<String> wasFuels = grades(was.fuels());
        List<String> nowFuels = grades(now.fuels());
        List<String> gone = relevant(minus(wasFuels, nowFuels), filter);
        List<String> appeared = relevant(minus(nowFuels, wasFuels), filter);
        boolean wasOpen = isOpen(was.status());
        boolean nowOpen = isOpen(now.status());
        List<String> lines = new ArrayList<>();

        if (wasOpen && !nowOpen) {
            // Closed: relevant if a watched grade disappeared, or we never knew which grades it had.
            if (!gone.isEmpty() || wasFuels.isEmpty()) {
                lines.add("🔴 Топливо закончилось");
                if (!gone.isEmpty()) {
                    lines.add(gradesChange("Закончился", "Закончились", gone));
                }
            }
            return lines;
        }
        if (!wasOpen && nowOpen && nowFuels.isEmpty()) {
            lines.add("🟢 Топливо появилось (марки не указаны)");
            return lines;
        }
        if (!gone.isEmpty()) {
            lines.add("🔴 " + gradesChange("Закончился", "Закончились", gone));
        }
        if (!appeared.isEmpty()) {
            lines.add("🟢 " + gradesChange("Появился", "Появились", appeared));
        }
        if (wasOpen && nowOpen && was.status() != now.status()
                && (nowFuels.isEmpty() || !relevant(nowFuels, filter).isEmpty())) {
            lines.add(now.status() == FuelStatus.QUEUE ? "🟡 Появилась очередь" : "🟢 Очередь пропала");
        }
        return lines;
    }

    /** HTML alert: station, change lines, the full current grade set, and the latest report's time (MSK). */
    static String alertText(String brand, String addr, List<String> changes, Essence now, CommentView latest) {
        StringBuilder sb = new StringBuilder("🔔 <b>").append(esc(brand == null || brand.isBlank() ? "АЗС" : brand))
                .append("</b>");
        if (addr != null && !addr.isBlank()) {
            sb.append(" · ").append(esc(addr));
        }
        for (String line : changes) {
            sb.append('\n').append(esc(line));
        }
        if (now.status() == FuelStatus.UNAVAILABLE) {
            if (latest != null && !latest.detail().isBlank()) {
                sb.append('\n').append(esc(latest.detail()));
            }
        } else if (!now.fuels().isEmpty()) {
            sb.append("\nСейчас есть: ").append(now.fuels().replace(",", ", "));
        }
        if (latest != null && latest.createdAt() != null) {
            sb.append("\nотметка в ").append(timeMsk(latest.createdAt())).append(" МСК");
            if (latest.onSite()) {
                sb.append(" · на месте");
            }
        }
        return sb.toString();
    }

    private static boolean isOpen(FuelStatus status) {
        return status == FuelStatus.AVAILABLE || status == FuelStatus.QUEUE;
    }

    private static List<String> grades(String csv) {
        return csv == null || csv.isEmpty() ? List.of() : List.of(csv.split(","));
    }

    private static List<String> minus(List<String> a, List<String> b) {
        return a.stream().filter(g -> !b.contains(g)).toList();
    }

    /** Grades the user watches: all when the filter is empty, else those selected ("95" covers "95+"). */
    private static List<String> relevant(List<String> grades, Set<String> filter) {
        if (filter == null || filter.isEmpty()) {
            return grades;
        }
        return grades.stream()
                .filter(g -> filter.contains(g) || ("95+".equals(g) && filter.contains("95")))
                .toList();
    }

    private static String gradesChange(String one, String many, List<String> grades) {
        return (grades.size() == 1 ? one : many) + " " + String.join(", ", grades);
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
