package ru.gdebenz.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gdebenz.fuel.StationView;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Per-user favorite stations, keyed by gdebenz {@code osm_id}. Capped at {@link #MAX_FAVORITES}
 * so all favorites always fit into one grid message.
 */
@Service
public class FavoriteService {

    /** Same as the grid's row cap: the favorites screen must fit into a single table. */
    public static final int MAX_FAVORITES = 8;

    public enum Toggle { ADDED, REMOVED, LIMIT_REACHED }

    private final FavoriteStationRepository repo;
    private final Clock clock;

    public FavoriteService(FavoriteStationRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** The osm_ids the user has starred, in the order they were added. */
    @Transactional(readOnly = true)
    public Set<String> ids(long userId) {
        Set<String> ids = new LinkedHashSet<>();
        for (FavoriteStation f : repo.findByUserIdOrderByCreatedAtAsc(userId)) {
            ids.add(f.getOsmId());
        }
        return ids;
    }

    @Transactional(readOnly = true)
    public long count(long userId) {
        return repo.countByUserId(userId);
    }

    /** Stars the station if it isn't a favorite yet (respecting the cap), otherwise un-stars it. */
    @Transactional
    public Toggle toggle(long userId, StationView s) {
        Optional<FavoriteStation> existing = repo.findByUserIdAndOsmId(userId, s.osmId());
        if (existing.isPresent()) {
            repo.delete(existing.get());
            return Toggle.REMOVED;
        }
        if (repo.countByUserId(userId) >= MAX_FAVORITES) {
            return Toggle.LIMIT_REACHED;
        }
        repo.save(new FavoriteStation(userId, s.osmId(), cap(s.brand(), 255), cap(s.addr(), 512),
                s.lat(), s.lon(), clock.instant()));
        return Toggle.ADDED;
    }

    private static String cap(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }
}
