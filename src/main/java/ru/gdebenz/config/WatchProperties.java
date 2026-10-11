package ru.gdebenz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Favorite-station poller settings, bound from {@code gdebenz.watch.*}. */
@ConfigurationProperties(prefix = "gdebenz.watch")
public record WatchProperties(
        boolean enabled,
        Duration interval,
        Duration requestPause,
        int commentsLimit) {

    public WatchProperties {
        if (interval == null) {
            interval = Duration.ofMinutes(1);
        }
        if (requestPause == null) {
            requestPause = Duration.ofMillis(500);
        }
        if (commentsLimit <= 0) {
            commentsLimit = 5;
        }
    }
}
