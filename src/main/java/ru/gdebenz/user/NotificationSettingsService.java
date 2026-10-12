package ru.gdebenz.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.function.UnaryOperator;

/**
 * Per-user settings for favorite-station alerts: on/off, "only grades from my filter", and quiet
 * hours (whole hours in MSK). Stored on {@code bot_users}; during quiet hours alerts are still sent,
 * but silently.
 */
@Service
public class NotificationSettingsService {

    /**
     * Snapshot of a user's alert settings; quiet hours are [from, to) in MSK and may wrap past midnight.
     * {@code useFilter} narrows alerts to the grades selected in /filters.
     */
    public record Settings(boolean enabled, boolean useFilter, boolean quietEnabled, int quietFrom, int quietTo) {

        public static final Settings DEFAULT = new Settings(true, false, true, 23, 7);

        /** Whether {@code hour} (0..23, MSK) falls into the quiet interval. from == to means never. */
        public boolean isQuiet(int hour) {
            if (!quietEnabled || quietFrom == quietTo) {
                return false;
            }
            return quietFrom < quietTo
                    ? hour >= quietFrom && hour < quietTo
                    : hour >= quietFrom || hour < quietTo;
        }

        public static Settings of(BotUser u) {
            return new Settings(u.isNotifyEnabled(), u.isNotifyUseFilter(), u.isQuietEnabled(),
                    u.getQuietFrom(), u.getQuietTo());
        }
    }

    private final BotUserRepository repo;
    private final Clock clock;

    public NotificationSettingsService(BotUserRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Settings get(long userId) {
        return repo.findById(userId).map(Settings::of).orElse(Settings.DEFAULT);
    }

    @Transactional
    public Settings toggleEnabled(long userId) {
        return update(userId, s -> new Settings(!s.enabled(), s.useFilter(), s.quietEnabled(), s.quietFrom(), s.quietTo()));
    }

    @Transactional
    public Settings toggleUseFilter(long userId) {
        return update(userId, s -> new Settings(s.enabled(), !s.useFilter(), s.quietEnabled(), s.quietFrom(), s.quietTo()));
    }

    @Transactional
    public Settings toggleQuiet(long userId) {
        return update(userId, s -> new Settings(s.enabled(), s.useFilter(), !s.quietEnabled(), s.quietFrom(), s.quietTo()));
    }

    /** Moves the quiet-hours start by {@code delta} hours, skipping the value equal to the end. */
    @Transactional
    public Settings shiftQuietFrom(long userId, int delta) {
        return update(userId, s -> new Settings(s.enabled(), s.useFilter(), s.quietEnabled(),
                shift(s.quietFrom(), delta, s.quietTo()), s.quietTo()));
    }

    /** Moves the quiet-hours end by {@code delta} hours, skipping the value equal to the start. */
    @Transactional
    public Settings shiftQuietTo(long userId, int delta) {
        return update(userId, s -> new Settings(s.enabled(), s.useFilter(), s.quietEnabled(),
                s.quietFrom(), shift(s.quietTo(), delta, s.quietFrom())));
    }

    private Settings update(long userId, UnaryOperator<Settings> change) {
        BotUser user = repo.findById(userId).orElse(null);
        if (user == null) {
            return Settings.DEFAULT;
        }
        Settings next = change.apply(Settings.of(user));
        user.setNotifications(next.enabled(), next.useFilter(), next.quietEnabled(), next.quietFrom(), next.quietTo(),
                clock.instant());
        repo.save(user);
        return next;
    }

    /** (hour + delta) mod 24, stepping once more if it would collide with {@code other} (an empty interval). */
    static int shift(int hour, int delta, int other) {
        int next = Math.floorMod(hour + delta, 24);
        if (next == other) {
            next = Math.floorMod(next + Integer.signum(delta), 24);
        }
        return next;
    }
}
