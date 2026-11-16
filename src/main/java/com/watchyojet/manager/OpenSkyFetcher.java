package com.watchyojet.manager;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchyojet.model.Aircraft;
import com.watchyojet.model.AircraftType;
import com.watchyojet.util.Logs;

public class OpenSkyFetcher {

    private static final System.Logger LOG = Logs.get(OpenSkyFetcher.class);

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
        "https://opensky-network.org/api/states/all?lamin=38.8&lomin=-76.5&lamax=40.9&lomax=-74.0&extended=1";

    static final String TOKEN_URL =
        "https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token";

    /** Anonymous access allows 400 credits a day, so poll less often without an account. */
    static final long ANONYMOUS_POLL_MS = 20_000;
    /** A free OpenSky account allows 4,000 credits a day. */
    static final long AUTHENTICATED_POLL_MS = 12_000;

    /**
     * OpenSky API client credentials (OAuth2 client credentials flow). Read from the
     * {@code OPENSKY_CLIENT_ID} and {@code OPENSKY_CLIENT_SECRET} environment variables, or the
     * {@code opensky.clientId} and {@code opensky.clientSecret} system properties.
     */
    public record Credentials(String clientId, String clientSecret) {
        public static Optional<Credentials> fromEnvironment() {
            String id = firstNonBlank(System.getProperty("opensky.clientId"), System.getenv("OPENSKY_CLIENT_ID"));
            String secret = firstNonBlank(System.getProperty("opensky.clientSecret"),
                    System.getenv("OPENSKY_CLIENT_SECRET"));
            return id == null || secret == null ? Optional.empty() : Optional.of(new Credentials(id, secret));
        }

        @Override
        public String toString() {
            return "Credentials[clientId=" + clientId + ", clientSecret=hidden]";
        }
    }

    record AccessToken(String value, long expiresAtMillis) {
        boolean usableAt(long nowMillis) {
            return nowMillis < expiresAtMillis;
        }
    }

    private final HttpClient   client;
    private final ObjectMapper mapper;
    private final Optional<Credentials> credentials;
    private AccessToken token;
    private OptionalLong creditsRemaining = OptionalLong.empty();

    public OpenSkyFetcher() {
        this(Credentials.fromEnvironment());
    }

    OpenSkyFetcher(Optional<Credentials> credentials) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
        this.credentials = credentials;
    }

    public boolean isAuthenticated() {
        return credentials.isPresent();
    }

    /** Poll interval that keeps a day of use within the account's credit allowance. */
    public long recommendedPollMillis() {
        return isAuthenticated() ? AUTHENTICATED_POLL_MS : ANONYMOUS_POLL_MS;
    }

    /** Credits left today, as reported by the last response, when OpenSky sent the header. */
    public OptionalLong creditsRemaining() {
        return creditsRemaining;
    }

    public List<Aircraft> fetchLiveTraffic() {
        return fetchLiveTrafficResult().aircraft();
    }

    public FetchResult fetchLiveTrafficResult() {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(OPENSKY_URL))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .GET();
            Optional<String> bearer = accessToken();
            bearer.ifPresent(value -> builder.header("Authorization", "Bearer " + value));

            HttpResponse<String> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            creditsRemaining = headerLong(response.headers(), "X-Rate-Limit-Remaining");

            if (response.statusCode() == 401 && bearer.isPresent()) {
                token = null;   // fetch a fresh token on the next poll
                String message = "OpenSky rejected the access token; signing in again next poll";
                LOG.log(WARNING, message);
                return new FetchResult(false, List.of(), message);
            }

            if (response.statusCode() == 429) {
                return new FetchResult(false, List.of(), "OpenSky request limit reached",
                        rateLimitDelayMillis(response.headers()));
            }

            if (response.statusCode() != 200) {
                String message = "OpenSky returned HTTP " + response.statusCode();
                LOG.log(WARNING, message);
                return new FetchResult(false, List.of(), message);
            }

            return parseSnapshot(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FetchResult(false, List.of(), "OpenSky request interrupted");
        } catch (IOException e) {
            String message = "Failed to fetch OpenSky data: " + e.getMessage();
            LOG.log(WARNING, message);
            return new FetchResult(false, List.of(), message);
        }
    }

    /** A cached access token, refreshed shortly before it expires. Empty when anonymous. */
    private Optional<String> accessToken() throws InterruptedException {
        if (credentials.isEmpty()) return Optional.empty();
        long now = System.currentTimeMillis();
        if (token != null && token.usableAt(now)) return Optional.of(token.value());

        Credentials c = credentials.get();
        String form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(c.clientId(), StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(c.clientSecret(), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.log(WARNING, "OpenSky sign-in failed with HTTP " + response.statusCode()
                        + "; using anonymous access for this poll");
                return Optional.empty();
            }
            token = parseToken(response.body(), now).orElse(null);
            if (token == null) {
                LOG.log(WARNING, "OpenSky sign-in returned no access token; using anonymous access");
                return Optional.empty();
            }
            LOG.log(INFO, "Signed in to OpenSky");
            return Optional.of(token.value());
        } catch (IOException e) {
            LOG.log(WARNING, "OpenSky sign-in failed: " + e.getMessage() + "; using anonymous access");
            return Optional.empty();
        }
    }

    /** Parses a token response. The token is treated as expiring a minute early. */
    Optional<AccessToken> parseToken(String body, long nowMillis) throws IOException {
        JsonNode root = mapper.readTree(body);
        if (root == null || !root.path("access_token").isTextual()) return Optional.empty();
        long expiresIn = root.path("expires_in").asLong(1800);
        long safeSeconds = Math.max(0, expiresIn - 60);
        return Optional.of(new AccessToken(root.path("access_token").textValue(),
                nowMillis + TimeUnit.SECONDS.toMillis(safeSeconds)));
    }

    private static OptionalLong headerLong(HttpHeaders headers, String name) {
        Optional<String> value = headers.firstValue(name);
        if (value.isEmpty()) return OptionalLong.empty();
        try {
            return OptionalLong.of(Long.parseLong(value.get().trim()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
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

            int category = emitterCategory(state.path(17));
            Aircraft observed = new Aircraft(icao24, callsign, lat, lon, altitudeFeet, speedKnots,
                    track, typeFor(category, speedKnots, altitudeFeet));
            observed.setVerticalRate(verticalRateFeetPerMinute(state.path(11)));
            observed.setSquawk(squawk(state.path(14)));
            observed.setEmitterCategory(category);
            aircraft.add(observed);
        }

        return new FetchResult(true, aircraft, "Fetched " + aircraft.size() + " aircraft");
    }

    /** OpenSky reports vertical rate in m/s; implausible or missing values count as level. */
    static double verticalRateFeetPerMinute(JsonNode value) {
        double feetPerMinute = numericValue(value) * 196.85;
        if (!Double.isFinite(feetPerMinute) || Math.abs(feetPerMinute) > 8_000) return 0;
        return feetPerMinute;
    }

    /** A four-digit octal transponder code, or null when absent or malformed. */
    static String squawk(JsonNode value) {
        if (value == null || !value.isTextual()) return null;
        String code = value.textValue().trim();
        return code.matches("[0-7]{4}") ? code : null;
    }

    private static double numericValue(JsonNode value) {
        return value.isNumber() ? value.doubleValue() : Double.NaN;
    }

    /** ADS-B emitter category (index 17, sent when extended=1), or 0 when absent. */
    static int emitterCategory(JsonNode value) {
        if (value == null || !value.isInt()) return 0;
        int category = value.intValue();
        return category >= 0 && category <= 20 ? category : 0;
    }

    /**
     * Uses the reported emitter category when there is one: 2 light, 3 small, 4 large,
     * 5 high-vortex large, 6 heavy. Falls back to a guess from speed and altitude.
     */
    static AircraftType typeFor(int category, double speedKnots, double altFeet) {
        return switch (category) {
            case 2 -> AircraftType.C172;
            case 3, 7, 8 -> AircraftType.GENERIC;
            case 4, 5 -> AircraftType.B737;
            case 6 -> AircraftType.A350;
            default -> inferType(speedKnots, altFeet);
        };
    }

    private static AircraftType inferType(double speedKnots, double altFeet) {
        if (speedKnots > 400 || altFeet > 25000) return AircraftType.A320;
        if (speedKnots > 200)                    return AircraftType.B737;
        return AircraftType.C172;
    }
}
