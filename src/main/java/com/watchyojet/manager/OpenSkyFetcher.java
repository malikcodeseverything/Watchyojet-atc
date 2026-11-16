package com.watchyojet.manager;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchyojet.model.Aircraft;
import com.watchyojet.model.AircraftType;

public class OpenSkyFetcher {

    private static final long DEFAULT_RATE_LIMIT_DELAY_MS = 60_000;

    public record FetchResult(boolean successful, List<Aircraft> aircraft, String message,
                              long retryAfterMillis) {
        public FetchResult(boolean successful, List<Aircraft> aircraft, String message) {
            this(successful, aircraft, message, 0);
        }
        public FetchResult {
            aircraft = List.copyOf(aircraft);
        }
    }

    // PHL TRACON area: ~60 NM radius around Philadelphia International (39.87N, 75.24W)
    private static final String OPENSKY_URL =
        "https://opensky-network.org/api/states/all?lamin=38.8&lomin=-76.5&lamax=40.9&lomax=-74.0";

    private final HttpClient   client;
    private final ObjectMapper mapper;

    public OpenSkyFetcher() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    public List<Aircraft> fetchLiveTraffic() {
        return fetchLiveTrafficResult().aircraft();
    }

    public FetchResult fetchLiveTrafficResult() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPENSKY_URL))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                return new FetchResult(false, List.of(), "OpenSky request limit reached",
                        rateLimitDelayMillis(response.headers()));
            }

            if (response.statusCode() != 200) {
                String message = "OpenSky returned HTTP " + response.statusCode();
                System.out.println("[FETCHER] " + message);
                return new FetchResult(false, List.of(), message);
            }

            return parseSnapshot(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FetchResult(false, List.of(), "OpenSky request interrupted");
        } catch (IOException e) {
            String message = "Failed to fetch OpenSky data: " + e.getMessage();
            System.out.println("[FETCHER] " + message);
            return new FetchResult(false, List.of(), message);
        }
    }

    static long rateLimitDelayMillis(HttpHeaders headers) {
        return retryDelayMillis(headers, "X-Rate-Limit-Retry-After-Seconds")
                .or(() -> retryDelayMillis(headers, "Retry-After"))
                .orElse(DEFAULT_RATE_LIMIT_DELAY_MS);
    }

    private static Optional<Long> retryDelayMillis(HttpHeaders headers, String name) {
        return headers.firstValue(name).flatMap(value -> {
            try {
                long seconds = Long.parseLong(value.trim());
                return seconds > 0 ? Optional.of(TimeUnit.SECONDS.toMillis(seconds)) : Optional.empty();
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    FetchResult parseSnapshot(String body) throws IOException {
        JsonNode root = mapper.readTree(body);
        JsonNode states = root == null ? null : root.get("states");
        if (states == null || !states.isArray()) {
            return new FetchResult(false, List.of(), "OpenSky response had no states array");
        }

        List<Aircraft> aircraft = new ArrayList<>();
        for (JsonNode state : states) {
            if (!state.isArray() || state.size() < 11
                    || !state.get(0).isTextual() || !state.get(8).isBoolean()
                    || state.get(8).booleanValue()) {
                continue;
            }

            String icao24 = state.get(0).textValue().trim().toLowerCase(Locale.ROOT);
            if (!icao24.matches("[0-9a-f]{6}")) continue;

            String callsign = state.get(1).isTextual() ? state.get(1).textValue().trim() : "";
            if (callsign.isEmpty()) callsign = icao24;

            JsonNode altitude = state.get(7).isNull() ? state.path(13) : state.get(7);
            double lon = numericValue(state.get(5));
            double lat = numericValue(state.get(6));
            double altitudeFeet = numericValue(altitude) * 3.28084;
            double speedKnots = numericValue(state.get(9)) * 1.94384;
            double track = numericValue(state.get(10));

            if (!Double.isFinite(lat) || !Double.isFinite(lon)
                    || !Double.isFinite(altitudeFeet) || !Double.isFinite(speedKnots)
                    || !Double.isFinite(track)
                    || lat < -90 || lat > 90 || lon < -180 || lon > 180
                    || altitudeFeet < -2_000 || altitudeFeet > 100_000
                    || speedKnots < 0 || speedKnots > 2_000 || track < 0 || track >= 360) {
                continue;
            }

            aircraft.add(new Aircraft(icao24, callsign, lat, lon, altitudeFeet, speedKnots,
                    track, inferType(speedKnots, altitudeFeet)));
        }

        return new FetchResult(true, aircraft, "Fetched " + aircraft.size() + " aircraft");
    }

    private static double numericValue(JsonNode value) {
        return value.isNumber() ? value.doubleValue() : Double.NaN;
    }

    private AircraftType inferType(double speedKnots, double altFeet) {
        if (speedKnots > 400 || altFeet > 25000) return AircraftType.A320;
        if (speedKnots > 200)                    return AircraftType.B737;
        return AircraftType.C172;
    }
}
