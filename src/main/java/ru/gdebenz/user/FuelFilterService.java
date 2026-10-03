package ru.gdebenz.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-user fuel-grade filter: which grades {@code /nearby} should restrict the list to.
 * Stored as a CSV in {@code bot_users.fuel_filter} (null/blank = no filter = show all).
 * Selected grades are always kept in the canonical {@link #GRADES} order.
 */
@Service
public class FuelFilterService {

    /** Filterable grades, in display/storage order. */
    public static final List<String> GRADES = List.of("92", "95", "98", "100", "ДТ");

    private final BotUserRepository repo;
    private final Clock clock;

    public FuelFilterService(BotUserRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** The user's currently selected grades (empty = no filter). */
    @Transactional(readOnly = true)
    public Set<String> get(long userId) {
        return repo.findById(userId).map(u -> parse(u.getFuelFilter())).orElseGet(LinkedHashSet::new);
    }

    /** Flips one grade on/off and persists; returns the new selection. */
    @Transactional
    public Set<String> toggle(long userId, String grade) {
        if (!GRADES.contains(grade)) {
            return get(userId);
        }
        BotUser user = repo.findById(userId).orElse(null);
        if (user == null) {
            return new LinkedHashSet<>();
        }
        Set<String> selected = parse(user.getFuelFilter());
        if (!selected.remove(grade)) {
            selected.add(grade);
        }
        Set<String> ordered = ordered(selected);
        user.setFuelFilter(ordered.isEmpty() ? null : String.join(",", ordered), clock.instant());
        repo.save(user);
        return ordered;
    }

    /** Clears the filter (back to showing all). */
    @Transactional
    public void clear(long userId) {
        repo.findById(userId).ifPresent(u -> {
            u.setFuelFilter(null, clock.instant());
            repo.save(u);
        });
    }

    /** Parses a stored CSV into the known grades, in canonical order (ignoring unknown/blank tokens). */
    static Set<String> parse(String csv) {
        Set<String> raw = new LinkedHashSet<>();
        if (csv != null && !csv.isBlank()) {
            for (String token : csv.split(",")) {
                raw.add(token.trim());
            }
        }
        return ordered(raw);
    }

    private static Set<String> ordered(Set<String> grades) {
        Set<String> result = new LinkedHashSet<>();
        for (String g : GRADES) {
            if (grades.contains(g)) {
                result.add(g);
            }
        }
        return result;
    }
}
