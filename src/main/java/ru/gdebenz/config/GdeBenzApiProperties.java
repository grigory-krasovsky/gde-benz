package ru.gdebenz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings for the gdebenz.ru API client, bound from {@code gdebenz.api.*}.
 * Browser-like User-Agent and Referer are required to pass DDoS-Guard.
 */
@ConfigurationProperties(prefix = "gdebenz.api")
public record GdeBenzApiProperties(
        String baseUrl,
        String commentsUrl,
        String userAgent,
        String referer,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration cacheTtl,
        double radiusKm,
        int maxResults,
        int commentsLimit,
        int maxRetries) {

    public GdeBenzApiProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://gdebenz.ru/api/nearby";
        }
        if (commentsUrl == null || commentsUrl.isBlank()) {
            commentsUrl = "https://gdebenz.ru/api/comments";
        }
        if (userAgent == null || userAgent.isBlank()) {
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/140.0 Safari/537.36";
        }
        if (referer == null || referer.isBlank()) {
            referer = "https://gdebenz.ru/";
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(5);
        }
        if (requestTimeout == null) {
            requestTimeout = Duration.ofSeconds(10);
        }
        if (cacheTtl == null) {
            cacheTtl = Duration.ofSeconds(60);
        }
        if (radiusKm <= 0) {
            radiusKm = 10.0;
        }
        if (maxResults <= 0) {
            maxResults = 10;
        }
        if (commentsLimit <= 0) {
            commentsLimit = 12;
        }
        if (maxRetries < 0) {
            maxRetries = 2;
        }
    }
}
