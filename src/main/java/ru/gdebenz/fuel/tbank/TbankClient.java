package ru.gdebenz.fuel.tbank;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.gdebenz.config.TbankApiProperties;
import ru.gdebenz.fuel.Geo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Client for T-Bank Toplivo /api/v1/stations (bbox query). Uses a dedicated SSLContext that trusts
 * the Russian Trusted Root CA. Response field names are camelCase, so default Jackson naming is used.
 */
@Component
public class TbankClient {

    private final TbankApiProperties props;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public TbankClient(TbankApiProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .sslContext(TbankTls.buildContext(props.caResource()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public List<TbankStation> fetch(double lat, double lon) {
        double[] bb = Geo.boundingBox(lat, lon, props.radiusKm());
        String url = String.format(Locale.ROOT, "%s?minLat=%.6f&maxLat=%.6f&minLon=%.6f&maxLon=%.6f",
                props.baseUrl(), bb[0], bb[1], bb[2], bb[3]);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(props.requestTimeout())
                .header("User-Agent", props.userAgent())
                .header("Referer", props.referer())
                .header("Accept", "application/json")
                .GET()
                .build();

        TbankException last = null;
        for (int attempt = 0; attempt <= props.maxRetries(); attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new TbankException("T-Bank returned HTTP " + response.statusCode());
                }
                TbankResponse body = mapper.readValue(response.body(), TbankResponse.class);
                return body.payload().stream().map(TbankClient::toStation).filter(Objects::nonNull).toList();
            } catch (TbankException e) {
                last = e;
                backoff(attempt);
            } catch (IOException e) {
                last = new TbankException("I/O error calling T-Bank: " + e.getMessage());
                backoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TbankException("interrupted while calling T-Bank");
            }
        }
        throw last != null ? last : new TbankException("T-Bank request failed");
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(200L * (attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static TbankStation toStation(TbankStationDto dto) {
        String brand = dto.brand() != null && !dto.brand().isBlank() ? dto.brand() : dto.name();
        Map<String, String> byFuel = dto.statusByFuelType() == null ? Map.of() : dto.statusByFuelType();
        return new TbankStation(brand, dto.lat(), dto.lon(), dto.status(), byFuel,
                dto.confidence(), parseInstant(dto.lastTransactionAt()));
    }

    static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static class TbankException extends RuntimeException {
        public TbankException(String message) {
            super(message);
        }
    }
}
