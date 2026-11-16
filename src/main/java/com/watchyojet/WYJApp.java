package com.watchyojet;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class WYJApp extends Application {

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
        System.out.println("Killing Process...");

        if (controller != null) controller.shutdown();
    }
    public static void main(String[] args) {
        launch();
    }
}
