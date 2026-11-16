package com.watchyojet;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.watchyojet.engine.ATCEngine;
import com.watchyojet.manager.AircraftManager;
import com.watchyojet.manager.OpenSkyFetcher;
import com.watchyojet.simulation.DemoScenario;

public class Main {

    public static final boolean START_IN_DEMO = Boolean.getBoolean("watchyojet.demo");

    private static final long API_COOLDOWN_MS   = 12_000;
    private static final long DEMO_RESET_MS     = 60_000; // reset scenario every 60 s
    private static final long DEMO_PREVIEW_MS   = 8_000;
    private static final long MAX_STALE_MS      = 60_000;

    public static void main(String[] args) {
        run(new AtomicBoolean(true), new AtomicBoolean(START_IN_DEMO), new AtomicBoolean());
    }

    static void run(AtomicBoolean running, AtomicBoolean demoRequested, AtomicBoolean replayRequested) {

        AircraftManager manager = new AircraftManager();
        OpenSkyFetcher  fetcher = new OpenSkyFetcher();
        ATCEngine       engine  = new ATCEngine();
        long lastApiFetchTime = -API_COOLDOWN_MS;
        long apiPollDelay = API_COOLDOWN_MS;
        boolean liveFetchFailed = false;
        Boolean demoMode = null;
        long lastDemoResetTime = Long.MIN_VALUE;
        long lastSuccessfulFetchTime = Long.MIN_VALUE;
        String currentStatus = null;
        String nominalStatus = null;
        boolean fallbackDemoLoaded = false;

        System.out.println("Initializing WatchyoJet ATC simulation...");
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            long now = System.currentTimeMillis();

            boolean requestedMode = demoRequested.get();
            if (demoMode == null || demoMode != requestedMode) {
                demoMode = requestedMode;
                manager.reset(List.of());
                engine = new ATCEngine();
                lastDemoResetTime = Long.MIN_VALUE;
                lastSuccessfulFetchTime = Long.MIN_VALUE;
                liveFetchFailed = !demoMode && now - lastApiFetchTime < apiPollDelay;
                fallbackDemoLoaded = false;
                nominalStatus = demoMode ? "DEMO — deterministic offline traffic"
                        : "CONNECTING — waiting for OpenSky";
                currentStatus = publishStatusIfChanged(currentStatus, nominalStatus);
            }

            if (demoMode) {
                if (replayRequested.getAndSet(false) || lastDemoResetTime == Long.MIN_VALUE
                        || now - lastDemoResetTime >= DEMO_RESET_MS) {
                    manager.reset(DemoScenario.build());
                    engine = new ATCEngine();
                    WYJAppController controller = WYJAppController.getInstance();
                    if (controller != null) controller.resetDemoDisplay();
                    System.out.println("\n[DEMO] Scenario reset. Tracking "
                            + manager.getAircrafts().size() + " aircraft.");
                    lastDemoResetTime = now;
                }
            } else {
                if (now - lastApiFetchTime >= apiPollDelay) {
                    System.out.println("\n[SYSTEM] Fetching LIVE traffic (PHL Airspace)...");
                    OpenSkyFetcher.FetchResult result = fetcher.fetchLiveTrafficResult();
                    if (Thread.currentThread().isInterrupted()) break;

                    now = System.currentTimeMillis();
                    lastApiFetchTime = now;
                    apiPollDelay = Math.max(API_COOLDOWN_MS, result.retryAfterMillis());
                    if (demoRequested.get()) continue;
                    liveFetchFailed = !result.successful();
                    if (result.successful()) {
                        manager.syncWithLiveData(result.aircraft());
                        System.out.println("[SYSTEM] Airspace refreshed. Tracking "
                                + manager.getAircrafts().size() + " live flights.");
                        lastSuccessfulFetchTime = now;
                        fallbackDemoLoaded = false;
                        nominalStatus = "LIVE — OpenSky snapshot received";
                    } else {
                        System.out.println("[SYSTEM] " + result.message()
                                + "; retrying in " + apiPollDelay / 1000 + " seconds.");
                    }
                }

                if (liveFetchFailed && !fallbackDemoLoaded) {
                    boolean recentSnapshot = lastSuccessfulFetchTime != Long.MIN_VALUE
                            && now - lastSuccessfulFetchTime <= MAX_STALE_MS;
                    if (recentSnapshot && !manager.getAircrafts().isEmpty()) {
                        nominalStatus = "STALE — using a recent cached OpenSky snapshot";
                    } else {
                        manager.reset(DemoScenario.build());
                        fallbackDemoLoaded = true;
                        nominalStatus = "DEMO — live data unavailable or older than 60 seconds";
                        System.out.println("[SYSTEM] Live data unavailable or stale; loaded demo traffic.");
                    }
                }
                if (nominalStatus != null && (currentStatus == null || !currentStatus.startsWith("ERROR"))) {
                    currentStatus = publishStatusIfChanged(currentStatus, nominalStatus);
                }
            }

            try {
                boolean applyManeuvers = !demoMode || now - lastDemoResetTime >= DEMO_PREVIEW_MS;
                engine.runCycle(manager.getAircrafts(), applyManeuvers);
                if (currentStatus != null && currentStatus.startsWith("ERROR")
                        && nominalStatus != null) {
                    currentStatus = publishStatusIfChanged(currentStatus, nominalStatus);
                }
            } catch (RuntimeException e) {
                e.printStackTrace(System.err);
                currentStatus = publishStatusIfChanged(currentStatus,
                        "ERROR — engine cycle failed; retrying");
            }

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static String publishStatusIfChanged(String current, String next) {
        if (next.equals(current)) return current;
        WYJAppController controller = WYJAppController.getInstance();
        if (controller != null) {
            controller.log("DATA STATUS: " + next);
            controller.logToMap("DATA STATUS: " + next);
            controller.setDataStatus(next);
        }
        return next;
    }
}
