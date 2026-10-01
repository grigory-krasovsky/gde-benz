package ru.gdebenz.fuel;

import org.springframework.stereotype.Service;
import ru.gdebenz.config.GdeBenzApiProperties;
import ru.gdebenz.fuel.api.CommentDto;
import ru.gdebenz.fuel.api.NearbyResponse;
import ru.gdebenz.fuel.api.StationDto;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches nearby stations and per-station driver reports, maps them to display views,
 * and caches results for a short TTL to avoid hammering gdebenz.
 */
@Service
public class FuelAvailabilityService {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GdeBenzApiClient client;
    private final GdeBenzApiProperties props;
    private final Clock clock;
    private final Map<String, Cached<NearbyResult>> nearbyCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<List<CommentView>>> commentCache = new ConcurrentHashMap<>();

    public FuelAvailabilityService(GdeBenzApiClient client, GdeBenzApiProperties props, Clock clock) {
        this.client = client;
        this.props = props;
        this.clock = clock;
    }

    public NearbyResult nearby(double lat, double lon) {
        String key = String.format(Locale.ROOT, "%.5f:%.5f", lat, lon);
        return fromCacheOr(nearbyCache, key, () -> map(client.nearby(lat, lon), props.maxResults()));
    }

    public List<CommentView> comments(String osmId) {
        return fromCacheOr(commentCache, osmId, () -> filterRecent(
                mapComments(client.comments(osmId, props.commentsLimit())),
                clock.instant(), props.commentsMaxAge()));
    }

    private <T> T fromCacheOr(Map<String, Cached<T>> cache, String key, java.util.function.Supplier<T> loader) {
        Instant now = clock.instant();
        Cached<T> cached = cache.get(key);
        if (cached != null && Duration.between(cached.at(), now).compareTo(props.cacheTtl()) < 0) {
            return cached.value();
        }
        T value = loader.get();
        cache.put(key, new Cached<>(value, now));
        return value;
    }

    /** Pure mapping + sort + limit, extracted for testability. */
    static NearbyResult map(NearbyResponse response, int maxResults) {
        List<StationView> stations = response.stations().stream()
                .map(FuelAvailabilityService::toView)
                .sorted(Comparator.comparingInt((StationView s) -> s.status().rank())
                        .thenComparingDouble(StationView::distanceKm))
                .limit(maxResults)
                .toList();
        return new NearbyResult(stations, response.updated());
    }

    static List<CommentView> mapComments(List<CommentDto> comments) {
        return comments.stream()
                .map(c -> new CommentView(
                        FuelStatus.from(c.status()),
                        nullToEmpty(c.detail()),
                        parseTimestamp(c.createdAt()),
                        c.onSite(),
                        c.authorReliable() || c.authorTier() > 0))
                .toList();
    }

    /** Keeps only reports no older than maxAge; drops those with an unknown timestamp. */
    static List<CommentView> filterRecent(List<CommentView> comments, Instant now, Duration maxAge) {
        Instant cutoff = now.minus(maxAge);
        return comments.stream()
                .filter(c -> c.createdAt() != null && !c.createdAt().isBefore(cutoff))
                .toList();
    }

    private static StationView toView(StationDto dto) {
        return new StationView(
                dto.osmId(),
                dto.brand(),
                nullToEmpty(dto.addr()),
                dto.lat(),
                dto.lon(),
                dto.distanceKm(),
                FuelStatus.from(dto.status()),
                nullToEmpty(dto.detail()),
                nullToEmpty(dto.fuelsNow()),
                dto.confirmations(),
                parseTimestamp(dto.lastAt()));
    }

    static Instant parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim(), TS).atZone(MSK).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Cached<T>(T value, Instant at) {
    }
}
