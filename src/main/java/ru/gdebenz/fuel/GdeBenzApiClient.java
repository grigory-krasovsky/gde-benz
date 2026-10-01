package ru.gdebenz.fuel;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.gdebenz.config.GdeBenzApiProperties;
import ru.gdebenz.fuel.api.NearbyResponse;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Locale;

/**
 * Talks to gdebenz.ru/api/nearby. Uses a browser User-Agent + Referer and a shared cookie jar
 * (DDoS-Guard), forces Locale.ROOT in the URL, and retries with backoff on 5xx / I/O errors.
 * Deliberately does not request gzip to keep response handling simple.
 */
@Component
public class GdeBenzApiClient {

    private static final Logger log = LoggerFactory.getLogger(GdeBenzApiClient.class);

    private final GdeBenzApiProperties props;
    private final Clock clock;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public GdeBenzApiClient(GdeBenzApiProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;

        CookieManager cookies = new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        this.http = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        this.mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public NearbyResponse nearby(double lat, double lon) {
        String url = String.format(Locale.ROOT, "%s?lat=%.5f&lon=%.5f&radius_km=%s&_=%d",
                props.baseUrl(), lat, lon, formatRadius(props.radiusKm()), clock.millis());
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(props.requestTimeout())
                .header("User-Agent", props.userAgent())
                .header("Referer", props.referer())
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .GET()
                .build();

        ApiException last = null;
        for (int attempt = 0; attempt <= props.maxRetries(); attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if (code != 200) {
                    throw new ApiException("gdebenz returned HTTP " + code);
                }
                return mapper.readValue(response.body(), NearbyResponse.class);
            } catch (ApiException e) {
                last = e;
                log.warn("gdebenz request failed (attempt {}/{}): {}", attempt + 1, props.maxRetries() + 1, e.getMessage());
                backoff(attempt);
            } catch (IOException e) {
                last = new ApiException("I/O error calling gdebenz", e);
                log.warn("gdebenz I/O error (attempt {}/{})", attempt + 1, props.maxRetries() + 1, e);
                backoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException("interrupted while calling gdebenz", e);
            }
        }
        throw last != null ? last : new ApiException("gdebenz request failed");
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(200L * (attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Render the radius without a trailing ".0" (e.g. 10, not 10.0), Locale.ROOT. */
    private static String formatRadius(double radiusKm) {
        if (radiusKm == Math.rint(radiusKm)) {
            return Long.toString((long) radiusKm);
        }
        return String.format(Locale.ROOT, "%s", radiusKm);
    }

    /** Thrown when gdebenz is unreachable or returns a non-200 response. */
    public static class ApiException extends RuntimeException {
        public ApiException(String message) {
            super(message);
        }

        public ApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
