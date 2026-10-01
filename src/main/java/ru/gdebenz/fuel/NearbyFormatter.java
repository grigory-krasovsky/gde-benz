package ru.gdebenz.fuel;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** Renders a nearby result as a Telegram HTML message with per-station Yandex route links. */
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

        StringBuilder sb = new StringBuilder("⛽ <b>АЗС рядом</b>");
        if (result.updated() != null && !result.updated().isBlank()) {
            sb.append(" · данные на ").append(esc(result.updated()));
        }
        sb.append('\n');

        for (StationView s : result.stations()) {
            sb.append('\n').append(s.status().emoji()).append(" <b>").append(esc(s.brand())).append("</b>");
            if (!s.addr().isBlank()) {
                sb.append(" — ").append(esc(s.addr()));
            }
            sb.append(" · ").append(fmtKm(s.distanceKm())).append(" км\n");

            String info = !s.detail().isBlank()
                    ? s.detail()
                    : (!s.fuelsNow().isBlank() ? "Есть: " + s.fuelsNow() : statusWord(s.status()));
            sb.append("   ").append(esc(info));

            String fresh = freshness(s.lastAt());
            if (fresh != null) {
                sb.append(" · ").append(fresh);
            }
            if (s.confirmations() > 0) {
                sb.append(" · ").append(s.confirmations()).append(" подтв.");
            }
            sb.append('\n');
            sb.append("   <a href=\"")
                    .append(esc(routeUrl(originLat, originLon, s.lat(), s.lon())))
                    .append("\">🗺 Маршрут</a>\n");
        }
        return sb.toString();
    }

    /** Yandex Maps driving route from the user's point to the station (built ourselves, no API). */
    static String routeUrl(double fromLat, double fromLon, double toLat, double toLon) {
        return String.format(Locale.ROOT,
                "https://yandex.ru/maps/?rtext=%.6f,%.6f~%.6f,%.6f&rtt=auto",
                fromLat, fromLon, toLat, toLon);
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

    private static String statusWord(FuelStatus status) {
        return switch (status) {
            case AVAILABLE -> "Есть в наличии";
            case QUEUE -> "Есть, но очередь";
            case UNAVAILABLE -> "Нет топлива";
            case UNKNOWN -> "Статус неизвестен";
        };
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
