package ru.gdebenz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings for the T-Bank Toplivo API (supplementary source), bound from {@code gdebenz.tbank.*}.
 * {@code caResource} is a classpath PEM with the Russian Trusted Root CA (T-Bank's cert chain);
 * if absent, the client falls back to default trust and T-Bank calls simply fail (column shows "·").
 */
@ConfigurationProperties(prefix = "gdebenz.tbank")
public record TbankApiProperties(
        Boolean enabled,
        String baseUrl,
        String userAgent,
        String referer,
        String caResource,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration cacheTtl,
        double radiusKm,
        double matchRadiusMeters,
        int maxRetries) {

    public TbankApiProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://toplivo.tbank.ru/api/v1/stations";
        }
        if (userAgent == null || userAgent.isBlank()) {
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/140.0 Safari/537.36";
        }
        if (referer == null || referer.isBlank()) {
            referer = "https://toplivo.tbank.ru/";
        }
        if (caResource == null || caResource.isBlank()) {
            caResource = "/certs/russian-trusted-ca.pem";
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
        if (matchRadiusMeters <= 0) {
            matchRadiusMeters = 120.0;
        }
        if (maxRetries < 0) {
            maxRetries = 1;
        }
    }
}
