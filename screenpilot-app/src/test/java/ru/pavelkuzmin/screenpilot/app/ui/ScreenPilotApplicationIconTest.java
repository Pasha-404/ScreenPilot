package ru.pavelkuzmin.screenpilot.app.ui;

import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenPilotApplicationIconTest {

    @BeforeAll
    static void startJavaFxToolkit() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // The FXML test may already own the JavaFX toolkit in this JVM.
        }
    }

    @Test
    void suppliesMultipleWindowIconSizesFromPackagedResources() throws Exception {
        AtomicReference<Stage> stage = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Platform.runLater(() -> {
            Stage created = new Stage();
            ScreenPilotApplication.configureWindowIcons(created);
            stage.set(created);
            created.close();
            completed.countDown();
        });

        assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(stage.get().getIcons()).hasSize(6);
        assertThat(stage.get().getIcons()).allMatch(icon -> !icon.isError());
    }
}
