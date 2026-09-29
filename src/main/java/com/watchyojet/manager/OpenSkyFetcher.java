package com.watchyojet.manager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchyojet.model.Aircraft;
import com.watchyojet.model.AircraftType;

public class OpenSkyFetcher {

    public record FetchResult(boolean successful, List<Aircraft> aircraft, String message) {
        public FetchResult {
            aircraft = List.copyOf(aircraft);
        }
    }

    // PHL TRACON area: ~60 NM radius around Philadelphia International (39.87N, 75.24W)
    private static final String OPENSKY_URL =
        "https://opensky-network.org/api/states/all?lamin=38.8&lomin=-76.5&lamax=40.9&lomax=-74.0";
    private static final long MAX_STATE_AGE_SECONDS = 20;

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
        List<Aircraft> liveAircraft = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPENSKY_URL))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                String message = "OpenSky returned HTTP " + response.statusCode();
                System.out.println("[FETCHER] " + message);
                return new FetchResult(false, liveAircraft, message);
            }

            JsonNode rootNode  = mapper.readTree(response.body());
            JsonNode statesNode = rootNode.get("states");

            if (statesNode == null || !statesNode.isArray()) {
                return new FetchResult(false, liveAircraft, "OpenSky response had no states array");
            }

            for (JsonNode state : statesNode) {

                if (state.size() < 11) continue;

                // A stale vector creates a convincing but fictional projected
                // conflict. OpenSky index 4 is the last-contact epoch second.
                if (state.size() > 4 && !state.get(4).isNull()) {
                    long age = Math.max(0, System.currentTimeMillis() / 1_000L
                            - state.get(4).asLong());
                    if (age > MAX_STATE_AGE_SECONDS) continue;
                }

                // skip if essential fields are null
                if (state.get(1).isNull()  ||   // callsign
                    state.get(5).isNull()  ||   // longitude
                    state.get(6).isNull()  ||   // latitude
                    state.get(9).isNull()  ||   // velocity (m/s)
                    state.get(10).isNull())      // heading
                    continue;

                // OpenSky fields: barometric altitude is index 7, geometric is 13.
                double altMeters;
                if (!state.get(7).isNull()) {
                    altMeters = state.get(7).asDouble();
                } else if (state.size() > 13 && !state.get(13).isNull()) {
                    altMeters = state.get(13).asDouble();
                } else {
                    continue; // no usable altitude
                }

                if (state.size() > 8 && state.get(8).asBoolean(false)) continue;

                String callsign = state.get(1).asText().trim();
                String icao24 = state.get(0).asText().trim();
                if (!icao24.matches("(?i)[0-9a-f]{6}")) continue;
                if (callsign.isEmpty()) callsign = icao24;

                double lon          = state.get(5).asDouble();
                double lat          = state.get(6).asDouble();
                double altitudeFeet = altMeters * 3.28084;
                double speedKnots   = state.get(9).asDouble() * 1.94384;
                double heading      = state.get(10).asDouble();
                double verticalRateFpm = state.size() > 11 && !state.get(11).isNull()
                        ? state.get(11).asDouble() * 196.850394
                        : 0.0;

                if (!Double.isFinite(lat) || !Double.isFinite(lon)
                        || !Double.isFinite(altitudeFeet) || !Double.isFinite(speedKnots)
                        || !Double.isFinite(heading)
                        || lat < -90 || lat > 90 || lon < -180 || lon > 180
                        || altitudeFeet < -2_000 || altitudeFeet > 100_000
                        || speedKnots < 0 || speedKnots > 2_000
                        || !Double.isFinite(verticalRateFpm)
                        || Math.abs(verticalRateFpm) > 8_000) {
                    continue;
                }

                AircraftType type = inferType(speedKnots, altitudeFeet);

                Aircraft aircraft = new Aircraft(icao24, callsign, lat, lon,
                        altitudeFeet, speedKnots, heading, type);
                aircraft.setVerticalRateFpm(verticalRateFpm);
                liveAircraft.add(aircraft);
            }

            return new FetchResult(true, liveAircraft,
                    "Fetched " + liveAircraft.size() + " aircraft");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FetchResult(false, liveAircraft, "OpenSky request interrupted");
        } catch (Exception e) {
            String message = "Failed to fetch OpenSky data: " + e.getMessage();
            System.out.println("[FETCHER] " + message);
            return new FetchResult(false, liveAircraft, message);
        }
    }

    private AircraftType inferType(double speedKnots, double altFeet) {
        if (speedKnots > 400 || altFeet > 25000) return AircraftType.A320;
        if (speedKnots > 200)                    return AircraftType.B737;
        return AircraftType.C172;
    }
}
