package com.watchyojet;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import static java.lang.System.Logger.Level.INFO;

import java.io.IOException;

import com.watchyojet.util.Logs;

public class WYJApp extends Application {

    private static final System.Logger LOG = Logs.get(WYJApp.class);

    private WYJAppController controller;

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(WYJApp.class.getResource("/main-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1180, 740);
        stage.setTitle("WatchyoJet ATC");
        stage.setScene(scene);
        stage.setMinWidth(820);
        stage.setMinHeight(540);

        controller = fxmlLoader.getController();
        stage.show();
    }
    @Override
    public void stop() {
        LOG.log(INFO, "Shutting down");

        if (controller != null) controller.shutdown();
    }
    public static void main(String[] args) {
        launch();
    }
}
