package ru.gdebenz.fuel;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders a nearby result as a Telegram HTML message. Per station, visible lines are:
 * name, fuels, queue (if any) and the route link; address / distance / limit / confirmations /
 * freshness go into an expandable blockquote below.
 */
@Component
public class NearbyFormatter {

    private final Clock clock;

    public NearbyFormatter(Clock clock) {
        this.clock = clock;
    }

    public String format(NearbyResult result, double originLat, double originLon) {
        if (result.stations().isEmpty()) {
            return "Рядом ничего не нашлось. Попробуй ещё раз позже.";
        }
        StringBuilder sb = new StringBuilder("⛽ <b>АЗС рядом</b>\n");
        for (StationView s : result.stations()) {
            sb.append('\n').append(card(s, originLat, originLon));
        }
        return sb.toString();
    }

    private String card(StationView s, double originLat, double originLon) {
        List<String> segments = segments(s.detail());
        List<String> lines = new ArrayList<>();

        // 1) name (with a status emoji)
        lines.add(s.status().emoji() + " <b>" + esc(s.brand()) + "</b>");
        // 2) fuels (or, for a closed station, the reason)
        String second = secondLine(s, segments);
        if (second != null) {
            lines.add(second);
        }
        // 3) queue, if known
        String queue = queueBadge(segments, s.status());
        if (queue != null) {
            lines.add(esc(queue));
        }
        // 4) route link
        lines.add("<a href=\"" + esc(routeUrl(originLat, originLon, s.lat(), s.lon())) + "\">🗺 Маршрут</a>");

        // 5) everything else, collapsed
        return String.join("\n", lines) + "\n" + collapsible(s, segments);
    }

    private String secondLine(StationView s, List<String> segments) {
        if (s.status() == FuelStatus.UNAVAILABLE) {
            return esc(reasonOf(segments));
        }
        String fuels = fuelsOf(s, segments);
        if (!fuels.isEmpty()) {
            return esc(fuels);
        }
        if (s.status() == FuelStatus.UNKNOWN && !segments.isEmpty()) {
            return esc(segments.get(0));
        }
        return null;
    }

    private String collapsible(StationView s, List<String> segments) {
        String line1 = String.join(" · ", nonBlank(
                s.addr().isBlank() ? null : esc(s.addr()),
                fmtKm(s.distanceKm()) + " км",
                esc(limitOf(segments))));
        String line2 = String.join(" · ", nonBlank(
                s.confirmations() > 0 ? s.confirmations() + " подтв." : null,
                freshness(s.lastAt())));
        String body = String.join("\n", nonBlank(emptyToNull(line1), emptyToNull(line2)));
        if (body.isEmpty()) {
            return "";
        }
        return "<blockquote expandable>" + body + "</blockquote>\n";
    }

    /** Yandex Maps driving route from the user's point to the station (built ourselves, no API). */
    static String routeUrl(double fromLat, double fromLon, double toLat, double toLon) {
        return String.format(Locale.ROOT,
                "https://yandex.ru/maps/?rtext=%.6f,%.6f~%.6f,%.6f&rtt=auto",
                fromLat, fromLon, toLat, toLon);
    }

    private static List<String> segments(String detail) {
        List<String> out = new ArrayList<>();
        if (detail != null && !detail.isBlank()) {
            for (String part : detail.split("·")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
        }
        return out;
    }

    private static String fuelsOf(StationView s, List<String> segments) {
        if (!s.fuelsNow().isBlank()) {
            return s.fuelsNow().replace(",", ", ");
        }
        if (!segments.isEmpty()) {
            String first = segments.get(0);
            if (!first.contains("Очередь") && !first.contains("Лимит") && looksLikeFuels(first)) {
                return first;
            }
        }
        return "";
    }

    private static boolean looksLikeFuels(String text) {
        String lower = text.toLowerCase();
        return lower.chars().anyMatch(Character::isDigit) || lower.contains("дт");
    }

    private static String queueBadge(List<String> segments, FuelStatus status) {
        String queue = segments.stream().filter(x -> x.contains("Очередь")).findFirst().orElse(null);
        if (queue == null) {
            return status == FuelStatus.QUEUE ? "⏳ очередь" : null;
        }
        String rest = queue.replace("Очередь", "").trim();
        return rest.isEmpty() ? "⏳ очередь" : "⏳ " + rest;
    }

    private static String limitOf(List<String> segments) {
        return segments.stream().filter(x -> x.contains("Лимит")).findFirst().orElse(null);
    }

    private static String reasonOf(List<String> segments) {
        return segments.isEmpty() ? "нет топлива" : segments.get(0);
    }

    private String freshness(Instant lastAt) {
        if (lastAt == null) {
            return null;
        }
        Duration d = Duration.between(lastAt, clock.instant());
        if (d.isNegative() || d.toMinutes() < 1) {
            return "только что";
        }
        long minutes = d.toMinutes();
        if (minutes < 60) {
            return minutes + " мин назад";
        }
        long hours = d.toHours();
        if (hours < 24) {
            return hours + " ч назад";
        }
        return d.toDays() + " дн назад";
    }

    private static List<String> nonBlank(String... items) {
        List<String> out = new ArrayList<>();
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                out.add(item);
            }
        }
        return out;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String fmtKm(double km) {
        return String.format(Locale.ROOT, "%.1f", km);
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
