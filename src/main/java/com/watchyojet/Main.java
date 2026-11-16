package com.watchyojet;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.watchyojet.engine.ATCEngine;
import com.watchyojet.engine.CycleListener;
import com.watchyojet.engine.CycleMode;
import com.watchyojet.manager.AircraftManager;
import com.watchyojet.manager.OpenSkyFetcher;
import com.watchyojet.model.Aircraft;
import com.watchyojet.simulation.DemoScenario;
import com.watchyojet.util.Logs;

public class Main {

    private static final System.Logger LOG = Logs.get(Main.class);

    public static final boolean START_IN_DEMO = Boolean.getBoolean("watchyojet.demo");

    private static final long DEMO_RESET_MS     = 60_000; // reset scenario every 60 s
    private static final long DEMO_PREVIEW_MS   = 8_000;
    private static final long MAX_STALE_MS      = 60_000;

    public static void main(String[] args) {
        run(new AtomicBoolean(true), new AtomicBoolean(START_IN_DEMO), new AtomicBoolean());
    }

    static void run(AtomicBoolean running, AtomicBoolean demoRequested, AtomicBoolean replayRequested) {

        AircraftManager manager = new AircraftManager();
        OpenSkyFetcher  fetcher = new OpenSkyFetcher();
        ATCEngine       engine  = newEngine();
        long basePollDelay = fetcher.recommendedPollMillis();
        long lastApiFetchTime = -basePollDelay;
        long apiPollDelay = basePollDelay;
        boolean liveFetchFailed = false;
        Boolean demoMode = null;
        long lastDemoResetTime = Long.MIN_VALUE;
        long lastSuccessfulFetchTime = Long.MIN_VALUE;
        String currentStatus = null;
        String nominalStatus = null;
        boolean fallbackDemoLoaded = false;

        LOG.log(INFO, "Initializing WatchyoJet ATC simulation");
        LOG.log(INFO, fetcher.isAuthenticated()
                ? "OpenSky: signing in with client credentials, polling every " + basePollDelay / 1000 + " s"
                : "OpenSky: anonymous access, polling every " + basePollDelay / 1000 + " s"
                        + " (set OPENSKY_CLIENT_ID and OPENSKY_CLIENT_SECRET for a higher daily allowance)");
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            long now = System.currentTimeMillis();

            boolean requestedMode = demoRequested.get();
            if (demoMode == null || demoMode != requestedMode) {
                demoMode = requestedMode;
                manager.reset(List.of());
                engine = newEngine();
                lastDemoResetTime = Long.MIN_VALUE;
                lastSuccessfulFetchTime = Long.MIN_VALUE;
                liveFetchFailed = !demoMode && now - lastApiFetchTime < apiPollDelay;
                fallbackDemoLoaded = false;
                nominalStatus = demoMode ? "DEMO · deterministic offline traffic"
                        : "CONNECTING · waiting for OpenSky";
                currentStatus = publishStatusIfChanged(currentStatus, nominalStatus);
            }

            if (demoMode) {
                if (replayRequested.getAndSet(false) || lastDemoResetTime == Long.MIN_VALUE
                        || now - lastDemoResetTime >= DEMO_RESET_MS) {
                    manager.reset(DemoScenario.build());
                    engine = newEngine();
                    WYJAppController controller = WYJAppController.getInstance();
                    if (controller != null) controller.resetDemoDisplay();
                    LOG.log(INFO, "[DEMO] Scenario reset. Tracking "
                            + manager.getAircrafts().size() + " aircraft.");
                    lastDemoResetTime = now;
                }
            } else {
                if (now - lastApiFetchTime >= apiPollDelay) {
                    LOG.log(INFO, "[SYSTEM] Fetching live traffic around PHL");
                    OpenSkyFetcher.FetchResult result = fetcher.fetchLiveTrafficResult();
                    if (Thread.currentThread().isInterrupted()) break;

                    now = System.currentTimeMillis();
                    lastApiFetchTime = now;
                    apiPollDelay = Math.max(basePollDelay, result.retryAfterMillis());
                    if (demoRequested.get()) continue;
                    liveFetchFailed = !result.successful();
                    if (result.successful()) {
                        manager.syncWithLiveData(result.aircraft(), now);
                        long coasting = manager.getAircrafts().stream().filter(Aircraft::isCoasting).count();
                        String credits = fetcher.creditsRemaining().isPresent()
                                ? " OpenSky credits left today: " + fetcher.creditsRemaining().getAsLong() + "."
                                : "";
                        LOG.log(INFO, "[SYSTEM] Airspace refreshed. Tracking "
                                + manager.getAircrafts().size() + " live flights"
                                + (coasting > 0 ? " (" + coasting + " coasting)" : "") + "." + credits);
                        lastSuccessfulFetchTime = now;
                        if (fallbackDemoLoaded) engine = newEngine();
                        fallbackDemoLoaded = false;
                        nominalStatus = "LIVE · OpenSky snapshot received";
                    } else {
                        LOG.log(WARNING, "[SYSTEM] " + result.message()
                                + "; retrying in " + apiPollDelay / 1000 + " seconds.");
                    }
                }

                if (liveFetchFailed && !fallbackDemoLoaded) {
                    boolean recentSnapshot = lastSuccessfulFetchTime != Long.MIN_VALUE
                            && now - lastSuccessfulFetchTime <= MAX_STALE_MS;
                    if (recentSnapshot && !manager.getAircrafts().isEmpty()) {
                        nominalStatus = "STALE · using a recent cached OpenSky snapshot";
                    } else {
                        manager.reset(DemoScenario.build());
                        engine = newEngine();
                        fallbackDemoLoaded = true;
                        nominalStatus = "DEMO · live data unavailable or older than 60 seconds";
                        LOG.log(WARNING, "[SYSTEM] Live data unavailable or stale; loaded demo traffic.");
                    }
                }
                if (nominalStatus != null && (currentStatus == null || !currentStatus.startsWith("ERROR"))) {
                    currentStatus = publishStatusIfChanged(currentStatus, nominalStatus);
                }
            }

            try {
                engine.runCycle(manager.getAircrafts(),
                        cycleMode(demoMode, fallbackDemoLoaded, now - lastDemoResetTime));
                if (currentStatus != null && currentStatus.startsWith("ERROR")
                        && nominalStatus != null) {
                    currentStatus = publishStatusIfChanged(currentStatus, nominalStatus);
                }
            } catch (RuntimeException e) {
                LOG.log(ERROR, "Engine cycle failed", e);
                currentStatus = publishStatusIfChanged(currentStatus,
                        "ERROR · engine cycle failed; retrying");
            }

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Observed OpenSky traffic is only ever advised on. Scripted traffic, including the
     * fallback demo, applies maneuvers once the opening preview has passed.
     */
    static CycleMode cycleMode(boolean demoMode, boolean fallbackDemoLoaded, long sinceDemoReset) {
        if (fallbackDemoLoaded) return CycleMode.SIMULATE;
        if (!demoMode) return CycleMode.ADVISE;
        return sinceDemoReset >= DEMO_PREVIEW_MS ? CycleMode.SIMULATE : CycleMode.DETECT_ONLY;
    }

    private static ATCEngine newEngine() {
        CycleListener listener = WYJAppController.getInstance();
        return new ATCEngine(listener);
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
