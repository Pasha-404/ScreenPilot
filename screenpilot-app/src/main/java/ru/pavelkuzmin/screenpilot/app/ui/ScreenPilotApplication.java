package ru.pavelkuzmin.screenpilot.app.ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;

/** Production JavaFX entry point. Technical console tools remain available through BootstrapMain arguments. */
public final class ScreenPilotApplication extends Application {

    private ScreenPilotApplicationService service;
    private MainViewController controller;
    private boolean closing;

    public static void launchApplication(String[] args) {
        launch(ScreenPilotApplication.class, args);
    }

    @Override
    public void start(Stage primaryStage) throws IOException {
        UiStateStore store = new UiStateStore();
        service = new ScreenPilotApplicationService(store);
        controller = new MainViewController(store, service);

        URL layout = ScreenPilotApplication.class.getResource("/ru/pavelkuzmin/screenpilot/app/ui/main-view.fxml");
        if (layout == null) {
            throw new IOException("Main JavaFX layout was not found");
        }
        FXMLLoader loader = new FXMLLoader(layout);
        loader.setControllerFactory(type -> type == MainViewController.class ? controller : null);
        Pane root = loader.load();
        javafx.geometry.Rectangle2D desktop = Screen.getPrimary().getVisualBounds();
        ResponsiveWindowSize initialSize = ResponsiveWindowSize.forAvailableDesktop(
                desktop.getWidth(), desktop.getHeight());
        Scene scene = new Scene(root, initialSize.width(), initialSize.height());
        URL styles = ScreenPilotApplication.class.getResource("/ru/pavelkuzmin/screenpilot/app/ui/screenpilot.css");
        if (styles == null) {
            throw new IOException("ScreenPilot stylesheet was not found");
        }
        scene.getStylesheets().add(styles.toExternalForm());
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, controller::handleShortcut);

        primaryStage.setTitle("ScreenPilot");
        primaryStage.setMinWidth(ResponsiveWindowSize.MIN_WIDTH);
        primaryStage.setMinHeight(ResponsiveWindowSize.MIN_HEIGHT);
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(event -> {
            if (closing) {
                controller.close();
                return;
            }
            event.consume();
            closing = true;
            service.stopOutput(() -> javafx.application.Platform.runLater(primaryStage::close));
        });
        controller.attachStage(primaryStage);
        primaryStage.show();
        service.start();
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.close();
        }
        if (service != null) {
            service.close();
        }
    }
}
