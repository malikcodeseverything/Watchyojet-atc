package com.watchyojet;

import java.util.concurrent.atomic.AtomicBoolean;

import com.watchyojet.engine.ATCEngine;
import com.watchyojet.manager.AircraftManager;
import com.watchyojet.manager.OpenSkyFetcher;
import com.watchyojet.simulation.DemoScenario;

public class Main {

    public static final boolean DEMO_MODE = Boolean.getBoolean("watchyojet.demo");

    private static final long API_COOLDOWN_MS   = 12_000;
    private static final long DEMO_RESET_MS     = 60_000; // reset scenario every 60 s
    private static final long MAX_STALE_MS      = 60_000;

    public static void main(String[] args) {
        run(new AtomicBoolean(true));
    }

    public static void run(AtomicBoolean running) {

        AircraftManager manager = new AircraftManager();
        OpenSkyFetcher  fetcher = new OpenSkyFetcher();
        ATCEngine       engine  = new ATCEngine();
        long lastApiFetchTime = -API_COOLDOWN_MS;
        long lastDemoResetTime = Long.MIN_VALUE;
        long lastSuccessfulFetchTime = Long.MIN_VALUE;
        String currentStatus = null;
        String nominalStatus = null;
        boolean fallbackDemoLoaded = false;

        System.out.println("Initializing WatchyoJet traffic advisory simulation...");
        if (DEMO_MODE) {
            System.out.println("[DEMO] Running controlled scenario — 10 aircraft, 3 conflict pairs");
            nominalStatus = "DEMO — deterministic offline traffic";
            currentStatus = publishStatusIfChanged(currentStatus, nominalStatus);
        }

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            long now = System.currentTimeMillis();

            if (DEMO_MODE) {
                if (now - lastDemoResetTime > DEMO_RESET_MS) {
                    manager.reset(DemoScenario.build());
                    System.out.println("\n[DEMO] Scenario reset. Tracking "
                            + manager.getAircrafts().size() + " aircraft.");
                    lastDemoResetTime = now;
                }
            } else {
                if (now - lastApiFetchTime > API_COOLDOWN_MS) {
                    System.out.println("\n[SYSTEM] Fetching LIVE traffic (PHL Airspace)...");
                    OpenSkyFetcher.FetchResult result = fetcher.fetchLiveTrafficResult();
                    if (result.successful()) {
                        manager.syncWithLiveData(result.aircraft());
                        System.out.println("[SYSTEM] Airspace refreshed. Tracking "
                                + manager.getAircrafts().size() + " live flights.");
                        lastSuccessfulFetchTime = now;
                        fallbackDemoLoaded = false;
                        nominalStatus = "LIVE — OpenSky snapshot received";
                        currentStatus = publishStatusIfChanged(currentStatus, nominalStatus);
                    } else {
                        boolean recentSnapshot = lastSuccessfulFetchTime != Long.MIN_VALUE
                                && now - lastSuccessfulFetchTime <= MAX_STALE_MS;
                        if (recentSnapshot && !manager.getAircrafts().isEmpty()) {
                            System.out.println("[SYSTEM] " + result.message()
                                    + "; retaining " + manager.getAircrafts().size()
                                    + " last-known aircraft.");
                            nominalStatus = "STALE — using a recent cached OpenSky snapshot";
                            currentStatus = publishStatusIfChanged(currentStatus, nominalStatus);
                        } else {
                            if (!fallbackDemoLoaded) {
                                manager.reset(DemoScenario.build());
                                fallbackDemoLoaded = true;
                                System.out.println("[SYSTEM] Live data unavailable or stale; loaded demo traffic.");
                            }
                            nominalStatus = "DEMO — live data unavailable or older than 60 seconds";
                            currentStatus = publishStatusIfChanged(currentStatus, nominalStatus);
                        }
                    }
                    lastApiFetchTime = now;
                }
            }

            try {
                engine.runCycle(manager.getAircrafts());
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
