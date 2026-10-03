package ru.gdebenz.fuel;

import org.springframework.stereotype.Component;
import ru.gdebenz.fuel.tbank.TbankStation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
            sb.append('\n').append(formatStation(s, originLat, originLon));
        }
        return sb.toString();
    }

    /** Renders recent driver reports for one station. */
    public String formatComments(List<CommentView> comments) {
        if (comments.isEmpty()) {
            return "Отметок пока нет.";
        }
        StringBuilder sb = new StringBuilder("💬 <b>Последние отметки</b>\n");
        for (CommentView c : comments) {
            sb.append('\n').append(c.status().emoji()).append(' ');
            sb.append(c.detail().isBlank() ? statusWord(c.status()) : esc(c.detail()));
            String fresh = freshness(c.createdAt());
            if (fresh != null) {
                sb.append(" · ").append(fresh);
            }
            if (c.onSite()) {
                sb.append(" · на месте");
            }
            if (c.reliable()) {
                sb.append(" · ✓");
            }
        }
        return sb.toString();
    }

    private static String statusWord(FuelStatus status) {
        return switch (status) {
            case AVAILABLE -> "есть";
            case QUEUE -> "есть, очередь";
            case UNAVAILABLE -> "нет";
            case UNKNOWN -> "статус неизвестен";
        };
    }

    /** Renders a single station as one message body (name, fuels, queue, route, collapsed rest). */
    public String formatStation(StationView s, double originLat, double originLon) {
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
        // Route is a URL button on the message (not a text link) to avoid link-preview images.
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

    /** Grid cell for one fuel column: blended confidence "NN%" when gdebenz lists it now, else "—". */
    public static String fuelCell(StationView s, String column, Optional<TbankStation> tbank) {
        if (!hasFuelColumn(s.fuelsNow(), column)) {
            return "—";
        }
        return blendedConfidencePercent(s.confidence(), column, tbank) + "%";
    }

    /**
     * Confidence that a specific grade is available now, as a percentage (5% steps, capped at 99).
     * Base is gdebenz's station confidence; a T-Bank confirmation for that grade only raises it
     * toward 100% — T-Bank under-reports, so its "no"/"no data" never lowers the number.
     */
    public static int blendedConfidencePercent(double base, String column, Optional<TbankStation> tbank) {
        double value = clamp01(base);
        double boost = tbankBoost(column, tbank);
        if (boost > 0.0) {
            value = value + boost * (1.0 - value);
        }
        int pct = (int) (Math.round(value * 20.0) * 5L); // nearest 5%
        return Math.max(0, Math.min(99, pct));
    }

    private static double tbankBoost(String column, Optional<TbankStation> tbank) {
        if (tbank.isEmpty() || tbank.get().statusByFuelType() == null) {
            return 0.0;
        }
        String status = tbank.get().statusByFuelType().get(column);
        double weight = switch (status == null ? "" : status) {
            case "available" -> 0.6;
            case "maybe_available" -> 0.25;
            default -> 0.0; // not_available / no_data: T-Bank only confirms, never denies
        };
        if (weight == 0.0) {
            return 0.0;
        }
        Double confidence = tbank.get().confidence();
        return weight * (confidence == null ? 1.0 : clamp01(confidence));
    }

    /** Popup breakdown for a tapped fuel cell: the blended %, plus the raw gdebenz and T-Bank reads. */
    public static String fuelPopup(StationView s, String column, Optional<TbankStation> tbank) {
        if (!hasFuelColumn(s.fuelsNow(), column)) {
            return column + ": по gdebenz сейчас нет";
        }
        int pct = blendedConfidencePercent(s.confidence(), column, tbank);
        StringBuilder sb = new StringBuilder(column + ": есть · уверенность " + pct + "%\n");
        sb.append("gdebenz ").append(Math.round(clamp01(s.confidence()) * 100.0)).append('%');
        String word = tbankGradeWord(column, tbank);
        if (word != null) {
            sb.append(" · T-Банк: ").append(word);
        }
        return sb.toString();
    }

    private static String tbankGradeWord(String column, Optional<TbankStation> tbank) {
        if (tbank.isEmpty() || tbank.get().statusByFuelType() == null) {
            return null;
        }
        String status = tbank.get().statusByFuelType().get(column);
        return status == null ? null : tbankStatusWord(status);
    }

    private static double clamp01(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        return Math.min(value, 1.0);
    }

    /** Popup text (plain, for a callback alert) with the T-Bank read for one station. */
    public String formatTbankPopup(Optional<TbankStation> match) {
        if (match.isEmpty()) {
            return "T-Банк: нет данных по этой АЗС";
        }
        TbankStation t = match.get();
        StringBuilder sb = new StringBuilder("T-Банк");
        String fuels = tbankFuelLine(t.statusByFuelType());
        if (!fuels.isEmpty()) {
            sb.append('\n').append(fuels);
        }
        if (t.confidence() != null) {
            sb.append("\nуверенность ").append(Math.round(t.confidence() * 100)).append('%');
        }
        String fresh = freshness(t.lastTransactionAt());
        if (fresh != null) {
            sb.append("\nпокупка ").append(fresh);
        }
        return sb.toString();
    }

    private static String tbankFuelLine(Map<String, String> byFuel) {
        if (byFuel == null || byFuel.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String grade : new String[] {"92", "95", "98", "100", "diesel"}) {
            String value = byFuel.get(grade);
            if (value != null) {
                parts.add(tbankGradeLabel(grade) + " " + tbankStatusWord(value));
            }
        }
        return String.join(", ", parts);
    }

    private static String tbankGradeLabel(String grade) {
        return "diesel".equals(grade) ? "ДТ" : grade;
    }

    private static String tbankStatusWord(String status) {
        return switch (status) {
            case "available" -> "есть";
            case "maybe_available" -> "возможно";
            case "not_available" -> "нет";
            case "no_data" -> "н/д";
            default -> status;
        };
    }

    /** Availability for a grid column; the "95" column also counts "95+". */
    public static boolean hasFuelColumn(String fuelsNow, String column) {
        if ("95".equals(column)) {
            return hasFuel(fuelsNow, "95") || hasFuel(fuelsNow, "95+");
        }
        return hasFuel(fuelsNow, column);
    }

    /** Whether a specific grade (e.g. "95", "95+") is currently available, by exact token match. */
    public static boolean hasFuel(String fuelsNow, String grade) {
        if (fuelsNow == null || fuelsNow.isBlank()) {
            return false;
        }
        for (String token : fuelsNow.split(",")) {
            if (token.trim().equalsIgnoreCase(grade)) {
                return true;
            }
        }
        return false;
    }

    /** Yandex Maps driving route from the user's point to the station (built ourselves, no API). */
    public static String routeUrl(double fromLat, double fromLon, double toLat, double toLon) {
        return String.format(Locale.ROOT,
                "https://yandex.ru/maps/?rtext=%.6f,%.6f~%.6f,%.6f&rtt=auto",
                fromLat, fromLon, toLat, toLon);
    }

    /**
     * Yandex Maps link with the user (blue pin) and every listed station (red pins), so the driver
     * can see at a glance which one is on their way. Built ourselves, no API key. Note: Yandex {@code pt}
     * points are "lon,lat" (the opposite order of {@code rtext} used by {@link #routeUrl}).
     */
    public static String allStationsMapUrl(double originLat, double originLon, List<StationView> stations) {
        StringBuilder pt = new StringBuilder(String.format(Locale.ROOT, "%.6f,%.6f,pm2bll", originLon, originLat));
        for (StationView s : stations) {
            pt.append('~').append(String.format(Locale.ROOT, "%.6f,%.6f,pm2rdm", s.lon(), s.lat()));
        }
        return "https://yandex.ru/maps/?pt=" + pt;
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
