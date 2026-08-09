package ru.pavelkuzmin.screenpilot.app.ui;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MainViewFxmlTest {

    @BeforeAll
    static void startJavaFxToolkit() throws Exception {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // A future JavaFX test may have started the toolkit first.
        }
    }

    @Test
    void loadsTheStageSevenControlsFromFxml() throws Exception {
        AtomicReference<Parent> root = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        Platform.runLater(() -> {
            ScreenPilotApplicationService service = null;
            try {
                UiStateStore store = new UiStateStore();
                service = new ScreenPilotApplicationService(store);
                MainViewController controller = new MainViewController(store, service);
                URL layout = MainViewController.class.getResource("/ru/pavelkuzmin/screenpilot/app/ui/main-view.fxml");
                FXMLLoader loader = new FXMLLoader(layout);
                loader.setControllerFactory(type -> type == MainViewController.class ? controller : null);
                root.set(loader.load());
                controller.close();
            } catch (Throwable exception) {
                failure.set(exception);
            } finally {
                if (service != null) {
                    service.close();
                }
                loaded.countDown();
            }
        });

        assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(root.get()).isNotNull();
    }
}
