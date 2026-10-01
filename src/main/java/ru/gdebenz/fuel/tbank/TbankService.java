package ru.gdebenz.fuel.tbank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.gdebenz.config.TbankApiProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Supplementary T-Bank layer. Returns an area snapshot for cross-checking gdebenz stations.
 * Best-effort: any failure (TLS, timeout, missing CA) yields an empty snapshot so the gdebenz
 * grid keeps working and the T-Bank column just shows no data.
 */
@Service
public class TbankService {

    private static final Logger log = LoggerFactory.getLogger(TbankService.class);

    private final TbankClient client;
    private final TbankApiProperties props;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public TbankService(TbankClient client, TbankApiProperties props, Clock clock) {
        this.client = client;
        this.props = props;
        this.clock = clock;
    }

    public TbankSnapshot snapshot(double lat, double lon) {
        if (!props.enabled()) {
            return TbankSnapshot.EMPTY;
        }
        String key = String.format(Locale.ROOT, "%.4f:%.4f", lat, lon);
        Instant now = clock.instant();
        Cached cached = cache.get(key);
        if (cached != null && Duration.between(cached.at(), now).compareTo(props.cacheTtl()) < 0) {
            return cached.snapshot();
        }
        try {
            TbankSnapshot snapshot = new TbankSnapshot(client.fetch(lat, lon), props.matchRadiusMeters());
            cache.put(key, new Cached(snapshot, now));
            return snapshot;
        } catch (RuntimeException e) {
            log.warn("T-Bank unavailable, continuing without it: {}", e.getMessage());
            return TbankSnapshot.EMPTY;
        }
    }

    private record Cached(TbankSnapshot snapshot, Instant at) {
    }
}
