package ru.pavelkuzmin.screenpilot.app.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.image.Image;
import javafx.scene.layout.Pane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import ru.pavelkuzmin.screenpilot.app.ApplicationPaths;
import ru.pavelkuzmin.screenpilot.app.SingleInstanceGuard;

import java.io.IOException;
import java.net.URL;
import java.util.List;

/** Production JavaFX entry point. Technical console tools remain available through BootstrapMain arguments. */
public final class ScreenPilotApplication extends Application {

    private static final List<String> WINDOW_ICON_RESOURCES = List.of(
            "/ru/pavelkuzmin/screenpilot/app/ui/icons/screenpilot-icon-16.png",
            "/ru/pavelkuzmin/screenpilot/app/ui/icons/screenpilot-icon-32.png",
            "/ru/pavelkuzmin/screenpilot/app/ui/icons/screenpilot-icon-48.png",
            "/ru/pavelkuzmin/screenpilot/app/ui/icons/screenpilot-icon-64.png",
            "/ru/pavelkuzmin/screenpilot/app/ui/icons/screenpilot-icon-128.png",
            "/ru/pavelkuzmin/screenpilot/app/ui/icons/screenpilot-icon-256.png"
    );

    private ScreenPilotApplicationService service;
    private MainViewController controller;
    private SingleInstanceGuard instanceGuard;
    private String startupFailure;
    private boolean closeRequested;
    private boolean cleanupCompleted;

    public static void launchApplication(String[] args) {
        launch(ScreenPilotApplication.class, args);
    }

    @Override
    public void init() {
        ApplicationPaths.configureLogging();
        try {
            instanceGuard = SingleInstanceGuard.acquire(ApplicationPaths.localDataDirectory());
        } catch (IOException exception) {
            startupFailure = "Не удалось проверить, запущен ли другой экземпляр приложения.";
        }
    }

    @Override
    public void start(Stage primaryStage) throws IOException {
        if (startupFailure != null || instanceGuard == null || !instanceGuard.isOwner()) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.initOwner(primaryStage);
            alert.setTitle("ScreenPilot");
            alert.setHeaderText(startupFailure == null ? "ScreenPilot уже запущен." : startupFailure);
            alert.setContentText(startupFailure == null
                    ? "Закройте работающее окно ScreenPilot и повторите запуск."
                    : "Закройте ScreenPilot, проверьте доступ к папке данных пользователя и повторите запуск.");
            alert.showAndWait();
            Platform.exit();
            return;
        }
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
        configureWindowIcons(primaryStage);
        primaryStage.setMinWidth(Math.min(ResponsiveWindowSize.MIN_WIDTH,
                Math.max(ResponsiveWindowSize.ABSOLUTE_MIN_WIDTH, desktop.getWidth() - 24)));
        primaryStage.setMinHeight(Math.min(ResponsiveWindowSize.MIN_HEIGHT,
                Math.max(ResponsiveWindowSize.ABSOLUTE_MIN_HEIGHT, desktop.getHeight() - 24)));
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(event -> {
            if (cleanupCompleted) {
                return;
            }
            event.consume();
            if (closeRequested) {
                return;
            }
            closeRequested = true;
            service.stopOutput(() -> javafx.application.Platform.runLater(() -> {
                cleanupCompleted = true;
                primaryStage.close();
            }));
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
        if (instanceGuard != null) {
            try {
                instanceGuard.close();
            } catch (IOException ignored) {
                // The OS releases the lock at process exit even if the close itself failed.
            }
        }
    }

    static void configureWindowIcons(Stage stage) {
        for (String resource : WINDOW_ICON_RESOURCES) {
            URL icon = ScreenPilotApplication.class.getResource(resource);
            if (icon != null) {
                stage.getIcons().add(new Image(icon.toExternalForm(), false));
            }
        }
    }
}
