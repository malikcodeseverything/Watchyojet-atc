package com.watchyojet.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class OpenSkyFetcherTest {

    private final OpenSkyFetcher fetcher = new OpenSkyFetcher();

    @Test
    void honorsTheOpenSkyRetryDelayBeforeTheStandardHeader() {
        HttpHeaders headers = HttpHeaders.of(Map.of(
                "X-Rate-Limit-Retry-After-Seconds", List.of("120"),
                "Retry-After", List.of("30")), (name, value) -> true);

        assertEquals(120_000, OpenSkyFetcher.rateLimitDelayMillis(headers));
    }

    @Test
    void usesTheStandardRetryDelayOrFallsBackWhenNoDelayIsUsable() {
        HttpHeaders standard = HttpHeaders.of(Map.of(
                "X-Rate-Limit-Retry-After-Seconds", List.of("invalid"),
                "Retry-After", List.of("90")), (name, value) -> true);
        HttpHeaders missing = HttpHeaders.of(Map.of(), (name, value) -> true);

        assertEquals(90_000, OpenSkyFetcher.rateLimitDelayMillis(standard));
        assertEquals(60_000, OpenSkyFetcher.rateLimitDelayMillis(missing));
    }

    @Test
    void parsesValidRowsAndSkipsMalformedOrGroundTracks() throws IOException {
        var result = fetcher.parseSnapshot("""
                {"states": [
                  {"bad": "row"},
                  ["ABC123", null, null, null, null, -75, 40, null, false, 125, 90, null, null, 3000],
                  ["abc124", " TEST1 ", null, null, null, -75, 40, 2500, false, 100, 0, null, null, 3000],
                  ["abc125", "BADLON", null, null, null, "text", 40, 3000, false, 125, 90],
                  ["abc126", "BADTRK", null, null, null, -75, 40, 3000, false, 125, 360],
                  ["abc127", "GROUND", null, null, null, -75, 40, 0, true, 0, 90]
                ]}
                """);

        assertTrue(result.successful());
        assertEquals(2, result.aircraft().size());

        var anonymous = result.aircraft().getFirst();
        assertEquals("abc123", anonymous.getId());
        assertEquals("abc123", anonymous.getCallsign());
        assertEquals(9842.52, anonymous.getAltitude(), 0.01);
        assertEquals(242.98, anonymous.getSpeed(), 0.01);
        assertEquals(90, anonymous.getHeading());

        var identified = result.aircraft().getLast();
        assertEquals("TEST1", identified.getCallsign());
        assertEquals(8202.10, identified.getAltitude(), 0.01);
    }

    @Test
    void distinguishesEmptyTrafficFromAnInvalidSnapshot() throws IOException {
        var empty = fetcher.parseSnapshot("{\"states\": []}");
        assertTrue(empty.successful());
        assertTrue(empty.aircraft().isEmpty());

        assertFalse(fetcher.parseSnapshot("{}").successful());
        assertFalse(fetcher.parseSnapshot("null").successful());
    }
}
