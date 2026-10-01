package ru.gdebenz.fuel;

import org.springframework.stereotype.Service;
import ru.gdebenz.config.GdeBenzApiProperties;
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
 * Fetches nearby stations, maps them to display views (available first, then by distance),
 * and caches results per rounded location for a short TTL to avoid hammering gdebenz.
 */
@Service
public class FuelAvailabilityService {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter LAST_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GdeBenzApiClient client;
    private final GdeBenzApiProperties props;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public FuelAvailabilityService(GdeBenzApiClient client, GdeBenzApiProperties props, Clock clock) {
        this.client = client;
        this.props = props;
        this.clock = clock;
    }

    public NearbyResult nearby(double lat, double lon) {
        String key = String.format(Locale.ROOT, "%.5f:%.5f", lat, lon);
        Instant now = clock.instant();
        Cached cached = cache.get(key);
        if (cached != null && Duration.between(cached.at(), now).compareTo(props.cacheTtl()) < 0) {
            return cached.result();
        }
        NearbyResponse response = client.nearby(lat, lon);
        NearbyResult result = map(response, props.maxResults());
        cache.put(key, new Cached(result, now));
        return result;
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

    private static StationView toView(StationDto dto) {
        return new StationView(
                dto.brand(),
                nullToEmpty(dto.addr()),
                dto.lat(),
                dto.lon(),
                dto.distanceKm(),
                FuelStatus.from(dto.status()),
                nullToEmpty(dto.detail()),
                nullToEmpty(dto.fuelsNow()),
                dto.confirmations(),
                parseLastAt(dto.lastAt()));
    }

    static Instant parseLastAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim(), LAST_AT).atZone(MSK).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Cached(NearbyResult result, Instant at) {
    }
}
