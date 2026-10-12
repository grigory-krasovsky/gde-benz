package ru.gdebenz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Favorite-station poller settings, bound from {@code gdebenz.watch.*}. Status is polled every
 * {@code interval}; station chats less often ({@code chatInterval}), looking for {@code keywords}
 * (lower-case stems, e.g. "бензовоз", "привез").
 */
@ConfigurationProperties(prefix = "gdebenz.watch")
public record WatchProperties(
        boolean enabled,
        Duration interval,
        Duration chatInterval,
        Duration requestPause,
        int commentsLimit,
        int chatLimit,
        List<String> keywords) {

    public WatchProperties {
        if (interval == null) {
            interval = Duration.ofMinutes(1);
        }
        if (chatInterval == null) {
            chatInterval = Duration.ofMinutes(10);
        }
        if (requestPause == null) {
            requestPause = Duration.ofMillis(500);
        }
        if (commentsLimit <= 0) {
            commentsLimit = 5;
        }
        if (chatLimit <= 0) {
            chatLimit = 20;
        }
        if (keywords == null || keywords.isEmpty()) {
            keywords = List.of("бензовоз", "топливозаправщик", "привез", "завез", "подвез", "завоз", "слив", "разгруж");
        }
    }
}
