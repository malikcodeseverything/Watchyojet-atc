package com.watchyojet;


import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchyojet.engine.CycleListener;
import com.watchyojet.engine.CycleReport;
import com.watchyojet.model.Aircraft;
import com.watchyojet.model.Conflict;

public class WYJAppController implements CycleListener {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static WYJAppController instance;
    public static WYJAppController getInstance() {
        return instance;
    }

    @FXML
    private VBox topContainer;

    @FXML
    private WebView webView;

    @FXML
    private ListView<String> logList;

    @FXML
    private CheckMenuItem showLogs;

    @FXML
    private MenuItem replayDemo;

    @FXML
    private RadioMenuItem liveTraffic;

    @FXML
    private RadioMenuItem offlineDemo;

    private WebEngine webEngine;

    @FXML
    public void initialize() {
        instance = this;
        offlineDemo.setSelected(Main.START_IN_DEMO);
        liveTraffic.setSelected(!Main.START_IN_DEMO);
        replayDemo.setDisable(!Main.START_IN_DEMO);
        webEngine = webView.getEngine();

        spawnMainThread.setOnFailed(event -> {
            Throwable error = spawnMainThread.getException();
            String detail = error == null ? "unknown error" : error.getMessage();
            log("Engine stopped unexpectedly: " + detail);
            setDataStatus("ERROR — simulation engine stopped");
        });

        webEngine.getLoadWorker().stateProperty().addListener((observable, previous, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                refreshDataStatus();
                startEngine();
            } else if (state == Worker.State.FAILED) {
                log("The traffic map failed to load.");
                showLogs.setSelected(true);
                toggleLogs();
            }
        });

        URL url = getClass().getResource("/map.html");
        if (url != null) {
            webEngine.load(url.toExternalForm());
        } else {
            log("Map resource is missing; the traffic display cannot start.");
            showLogs.setSelected(true);
            toggleLogs();
        }
    }

    @FXML
    public void toggleLogs(){
            logList.setVisible(showLogs.isSelected());
            logList.setManaged(showLogs.isSelected());
    }

    public void log(String message) {
        Platform.runLater(() -> {
            logList.getItems().add(message);
            if (logList.getItems().size() > 500) logList.getItems().remove(0, 100);
            logList.scrollTo(logList.getItems().size() - 1);
        });
    }

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean demoRequested = new AtomicBoolean(Main.START_IN_DEMO);
    private final AtomicBoolean replayRequested = new AtomicBoolean();
    private volatile String currentDataStatus;

    Task<Void> spawnMainThread = new Task<>(){
        @Override
        protected Void call() {
            Main.run(running, demoRequested, replayRequested);
            return null;
        }
    };

    public void shutdown() {
        running.set(false);
        spawnMainThread.cancel(true);
    }

    public void startEngine() {
        if (!started.compareAndSet(false, true)) return;
        Thread engineThread = new Thread(spawnMainThread, "watchyojet-engine");
        engineThread.setDaemon(true);
        engineThread.start();
    }

    @FXML
    public void handleClose() {
        Stage stage = (Stage) topContainer.getScene().getWindow();
        stage.close();
    }

    @FXML
    public void selectLiveTraffic() {
        liveTraffic.setSelected(true);
        replayRequested.set(false);
        demoRequested.set(false);
        replayDemo.setDisable(true);
    }

    @FXML
    public void selectOfflineDemo() {
        offlineDemo.setSelected(true);
        demoRequested.set(true);
        replayDemo.setDisable(false);
    }

    @FXML
    public void replayDemo() {
        replayRequested.set(true);
    }

    public void resetDemoDisplay() {
        Platform.runLater(() -> {
            try {
                webEngine.executeScript("resetTrafficDisplay()");
            } catch (RuntimeException error) {
                reportWebError("demo reset", error);
            }
        });
    }

    public void updateAllAircraft(List<Aircraft> aircrafts) {
        final String aircraftJson;
        try {
            aircraftJson = JSON.writeValueAsString(aircrafts.stream().map(a -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", a.getId());
                item.put("cs", a.getCallsign());
                item.put("lat", a.getLat());
                item.put("lon", a.getLon());
                item.put("alt", a.getAltitude());
                item.put("spd", a.getSpeed());
                item.put("hdg", a.getHeading());
                item.put("vr", a.getVerticalRate());
                item.put("sq", a.getSquawk() == null ? "" : a.getSquawk());
                item.put("type", a.getType().name());
                return item;
            }).toList());
        } catch (JsonProcessingException e) {
            log("Unable to encode aircraft update: " + e.getMessage());
            return;
        }
        Platform.runLater(() -> {
            try {
                Object check = webEngine.executeScript("typeof batchUpdateAircraft !== 'undefined'");
                if (check.equals(true)) {
                    webEngine.executeScript("batchUpdateAircraft(" + aircraftJson + ")");
                }
            } catch (RuntimeException e) {
                reportWebError("aircraft update", e);
            }
        });
    }

    @Override
    public void onCycle(CycleReport report, List<Aircraft> aircraft) {
        updateAllAircraft(aircraft);
        publishConflicts(report);
    }

    private void publishConflicts(CycleReport report) {
        final String reportJson;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("mode", report.mode().name());
            payload.put("active", report.active().stream().map(alert -> {
                Conflict conflict = alert.conflict();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", alert.pairKey());
                item.put("id1", conflict.getA1().getId());
                item.put("id2", conflict.getA2().getId());
                item.put("callsign1", conflict.getA1().getCallsign());
                item.put("callsign2", conflict.getA2().getCallsign());
                item.put("severity", conflict.getSeverity().name());
                item.put("seconds", Math.round(conflict.getTCPA()));
                item.put("distance", conflict.getDCPA());
                item.put("firstSeen", alert.firstSeenMillis());
                item.put("advice", alert.advice().name());
                item.put("instruction", alert.instruction());
                return item;
            }).toList());
            payload.put("cleared", report.cleared().stream().map(alert -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", alert.pairKey());
                item.put("id1", alert.id1());
                item.put("id2", alert.id2());
                item.put("callsign1", alert.callsign1());
                item.put("callsign2", alert.callsign2());
                item.put("instruction", alert.instruction());
                return item;
            }).toList());
            CycleReport.Counters c = report.counters();
            Map<String, Object> counters = new LinkedHashMap<>();
            counters.put("alerts", c.alerts());
            counters.put("newAlerts", c.newAlerts());
            counters.put("clearedAlerts", c.clearedAlerts());
            counters.put("groups", c.groups());
            counters.put("proposals", c.proposals());
            counters.put("acceptedGroups", c.acceptedGroups());
            counters.put("rejectedGroups", c.rejectedGroups());
            counters.put("pairsClearedInTrial", c.pairsClearedInTrial());
            counters.put("noSafeManeuver", c.noSafeManeuver());
            counters.put("separationLost", c.separationLost());
            counters.put("clearing", c.clearing());
            counters.put("trafficAdvisories", c.trafficAdvisories());
            counters.put("towerSuppressed", c.towerSuppressed());
            payload.put("counters", counters);
            reportJson = JSON.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log("Unable to encode conflict update: " + e.getMessage());
            return;
        }
        Platform.runLater(() -> {
            try {
                Object check = webEngine.executeScript("typeof applyCycleReport !== 'undefined'");
                if (check.equals(true)) webEngine.executeScript("applyCycleReport(" + reportJson + ")");
            } catch (RuntimeException e) {
                reportWebError("conflict update", e);
            }
        });
    }

    public void logToMap(String message) {
        Platform.runLater(() -> {
            try {
                Object check = webEngine.executeScript("typeof logATCEvent !== 'undefined'");
                if (check.equals(true)) {
                    webEngine.executeScript("logATCEvent(" + json(message.replace('\n', ' ')) + ")");
                }
            } catch (RuntimeException e) {
                reportWebError("map log", e);
            }
        });
    }

    public void setDataStatus(String status) {
        currentDataStatus = status;
        Platform.runLater(() -> {
            try {
                Object check = webEngine.executeScript("typeof setDataStatus !== 'undefined'");
                if (check.equals(true)) webEngine.executeScript("setDataStatus(" + json(status) + ")");
            } catch (RuntimeException e) {
                reportWebError("data status", e);
            }
        });
    }

    public void refreshDataStatus() {
        String status = currentDataStatus;
        if (status != null) setDataStatus(status);
    }

    private static String json(String value) {
        try {
            return JSON.writeValueAsString(value == null ? "" : value);
        } catch (JsonProcessingException e) {
            return "\"\"";
        }
    }

    private void reportWebError(String operation, RuntimeException error) {
        String message = "Web UI " + operation + " failed: " + error.getMessage();
        System.err.println(message);
        log(message);
    }

}
