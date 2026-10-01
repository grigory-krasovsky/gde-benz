package ru.gdebenz.fuel;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders a nearby result as a compact Telegram HTML message:
 * one visible headline per station (status · name · fuels · queue), with address, distance,
 * limit, confirmations, freshness and the route link tucked into an expandable blockquote.
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
            sb.append('\n').append(headline(s)).append('\n');
            sb.append(details(s, originLat, originLon));
        }
        return sb.toString();
    }

    private String headline(StationView s) {
        List<String> segments = segments(s.detail());
        StringBuilder h = new StringBuilder(s.status().emoji())
                .append(" <b>").append(esc(s.brand())).append("</b>");
        switch (s.status()) {
            case AVAILABLE, QUEUE -> {
                String fuels = fuelsOf(s, segments);
                if (!fuels.isEmpty()) {
                    h.append(" — ").append(esc(fuels));
                }
                String queue = queueBadge(segments, s.status());
                if (queue != null) {
                    h.append(" · ").append(esc(queue));
                }
            }
            case UNAVAILABLE -> h.append(" — ").append(esc(reasonOf(segments)));
            case UNKNOWN -> {
                if (!segments.isEmpty()) {
                    h.append(" — ").append(esc(segments.get(0)));
                }
            }
        }
        return h.toString();
    }

    private String details(StationView s, double originLat, double originLon) {
        List<String> segments = segments(s.detail());
        String line1 = String.join(" · ", nonBlank(
                s.addr().isBlank() ? null : esc(s.addr()),
                fmtKm(s.distanceKm()) + " км",
                esc(limitOf(segments))));
        String line2 = String.join(" · ", nonBlank(
                s.confirmations() > 0 ? s.confirmations() + " подтв." : null,
                freshness(s.lastAt())));
        String route = "<a href=\"" + esc(routeUrl(originLat, originLon, s.lat(), s.lon())) + "\">🗺 Маршрут</a>";
        String body = String.join("\n", nonBlank(emptyToNull(line1), emptyToNull(line2), route));
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
