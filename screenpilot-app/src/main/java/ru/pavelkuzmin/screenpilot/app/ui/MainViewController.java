package ru.pavelkuzmin.screenpilot.app.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Slider;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Screen;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;
import ru.pavelkuzmin.screenpilot.domain.media.AudioOutputDevice;
import ru.pavelkuzmin.screenpilot.domain.media.MediaTrack;
import ru.pavelkuzmin.screenpilot.domain.media.MediaTrackKind;
import ru.pavelkuzmin.screenpilot.domain.session.OutputSessionState;
import ru.pavelkuzmin.screenpilot.domain.session.PlayerState;
import ru.pavelkuzmin.screenpilot.domain.settings.ScalingMode;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** FXML controller: it renders ApplicationState and delegates every command to the service. */
public final class MainViewController {

    private final UiStateStore store;
    private final ScreenPilotApplicationService service;
    private AutoCloseable stateSubscription;
    private boolean applyingState;
    private Stage stage;

    @FXML
    private Label displayStatus;
    @FXML
    private Label topologyStatus;
    @FXML
    private Label currentFile;
    @FXML
    private Label fileDetails;
    @FXML
    private Label feedback;
    @FXML
    private Label playbackTime;
    @FXML
    private ComboBox<DisplayInfo> targetSelector;
    @FXML
    private ComboBox<DisplayMode> modeSelector;
    @FXML
    private ComboBox<AudioOutputDevice> audioOutputSelector;
    @FXML
    private ComboBox<MediaTrack> audioTrackSelector;
    @FXML
    private Button openMediaButton;
    @FXML
    private Button refreshDisplaysButton;
    @FXML
    private Button startOutputButton;
    @FXML
    private Button pauseButton;
    @FXML
    private Button stopPlaybackButton;
    @FXML
    private Button seekBackwardButton;
    @FXML
    private Button seekForwardButton;
    @FXML
    private Button stopOutputButton;
    @FXML
    private Button fitButton;
    @FXML
    private Button fillButton;
    @FXML
    private Button oneToOneButton;
    @FXML
    private Slider progressSlider;
    @FXML
    private Slider volumeSlider;

    public MainViewController(UiStateStore store, ScreenPilotApplicationService service) {
        this.store = store;
        this.service = service;
    }

    @FXML
    private void initialize() {
        targetSelector.setCellFactory(ignored -> new DisplayCell());
        targetSelector.setButtonCell(new DisplayCell());
        modeSelector.setCellFactory(ignored -> new ModeCell());
        modeSelector.setButtonCell(new ModeCell());
        audioOutputSelector.setCellFactory(ignored -> new AudioOutputCell());
        audioOutputSelector.setButtonCell(new AudioOutputCell());
        audioTrackSelector.setCellFactory(ignored -> new AudioTrackCell());
        audioTrackSelector.setButtonCell(new AudioTrackCell());
        targetSelector.valueProperty().addListener((ignored, previous, selected) -> {
            if (!applyingState && selected != null && selected != previous) {
                service.selectTarget(selected);
            }
        });
        modeSelector.valueProperty().addListener((ignored, previous, selected) -> {
            if (!applyingState && selected != null && selected != previous) {
                service.selectMode(selected);
            }
        });
        audioOutputSelector.valueProperty().addListener((ignored, previous, selected) -> {
            if (!applyingState && selected != null && selected != previous) {
                service.selectAudioOutput(selected.id());
            }
        });
        audioTrackSelector.valueProperty().addListener((ignored, previous, selected) -> {
            if (!applyingState && selected != null && selected != previous) {
                service.selectAudioTrack(selected.id());
            }
        });
        stateSubscription = store.subscribe(state -> Platform.runLater(() -> applyState(state)));
    }

    void attachStage(Stage stage) {
        this.stage = stage;
    }

    @FXML
    private void openMedia() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выберите видеофайл");
        chooser.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter("Видео", "*.mkv", "*.mp4", "*.avi", "*.mov", "*.webm"),
                new FileChooser.ExtensionFilter("Все файлы", "*.*")
        );
        File file = chooser.showOpenDialog(stage);
        if (file != null) {
            service.selectMedia(file.toPath());
        }
    }

    @FXML
    private void refreshDisplays() {
        service.refreshDisplays();
    }

    @FXML
    private void startOutput() {
        ApplicationState state = store.current();
        DisplayInfo target = state.selectedTarget().orElse(null);
        if (target == null) {
            return;
        }
        Optional<Integer> candidate = MpvScreenResolver.resolve(target.bounds(), Screen.getScreens());
        if (candidate.isEmpty()) {
            showError("Не удалось сопоставить выбранный экран с окном mpv.",
                    "Вывод не будет запущен на встроенном экране как запасном варианте.");
            return;
        }
        if (!confirmDisplayPreparation(target)) {
            return;
        }
        service.startOutput(candidate.orElseThrow());
    }

    @FXML
    private void stopOutput() {
        service.stopOutput();
    }

    @FXML
    private void togglePause() {
        service.togglePause();
    }

    @FXML
    private void stopPlayback() {
        service.stopPlayback();
    }

    @FXML
    private void seekBackward() {
        service.seekRelative(Duration.ofSeconds(-10));
    }

    @FXML
    private void seekForward() {
        service.seekRelative(Duration.ofSeconds(10));
    }

    @FXML
    private void seekFromSlider() {
        if (!progressSlider.isDisabled()) {
            service.seek(Duration.ofMillis(Math.round(progressSlider.getValue() * 1_000)));
        }
    }

    @FXML
    private void commitVolume() {
        if (!volumeSlider.isDisabled()) {
            service.setVolume((int) Math.round(volumeSlider.getValue()));
        }
    }

    @FXML
    private void setFitScaling() {
        service.setScaling(ScalingMode.FIT);
    }

    @FXML
    private void setFillScaling() {
        service.setScaling(ScalingMode.FILL);
    }

    @FXML
    private void setOneToOneScaling() {
        service.setScaling(ScalingMode.ONE_TO_ONE);
    }

    void handleShortcut(KeyEvent event) {
        if (event.getTarget() instanceof TextInputControl) {
            return;
        }
        switch (event.getCode()) {
            case SPACE -> {
                service.togglePause();
                event.consume();
            }
            case LEFT -> {
                service.seekRelative(Duration.ofSeconds(event.isControlDown() ? -60 : -10));
                event.consume();
            }
            case RIGHT -> {
                service.seekRelative(Duration.ofSeconds(event.isControlDown() ? 60 : 10));
                event.consume();
            }
            case UP -> {
                changeVolumeBy(5);
                event.consume();
            }
            case DOWN -> {
                changeVolumeBy(-5);
                event.consume();
            }
            case ESCAPE -> {
                service.stopOutput();
                event.consume();
            }
            case O -> {
                if (event.isControlDown()) {
                    openMedia();
                    event.consume();
                }
            }
            default -> {
                // The app deliberately does not register global hotkeys.
            }
        }
    }

    void close() {
        if (stateSubscription != null) {
            try {
                stateSubscription.close();
            } catch (Exception ignored) {
                // Removing a listener is local cleanup and cannot invalidate application shutdown.
            }
        }
    }

    private void applyState(ApplicationState state) {
        applyingState = true;
        try {
            List<DisplayInfo> targets = state.externalTargets();
            targetSelector.setItems(FXCollections.observableArrayList(targets));
            boolean outputChanging = state.outputState() == OutputSessionState.PREPARING_DISPLAY
                    || state.outputState() == OutputSessionState.OUTPUT_IDLE
                    || state.outputState() == OutputSessionState.OUTPUT_ACTIVE
                    || state.outputState() == OutputSessionState.RESTORING_DISPLAY;
            openMediaButton.setDisable(outputChanging);
            refreshDisplaysButton.setDisable(outputChanging || state.refreshingDisplays());
            targetSelector.setDisable(state.refreshingDisplays() || targets.isEmpty() || outputChanging);
            targetSelector.setValue(state.selectedTarget().orElse(null));

            DisplayInfo target = state.selectedTarget().orElse(null);
            modeSelector.setItems(FXCollections.observableArrayList(
                    target == null ? List.of() : target.confirmedModes()));
            modeSelector.setValue(state.selectedMode());
            modeSelector.setDisable(target == null || target.confirmedModes().isEmpty()
                    || state.refreshingDisplays() || outputChanging);

            currentFile.setText(state.selectedMedia() == null ? "Видео пока не выбрано" : fileName(state.selectedMedia()));
            fileDetails.setText(state.selectedMedia() == null
                    ? "Откройте один локальный MKV или MP4. На ноутбуке preview не создаётся."
                    : state.selectedMedia().toString());
            feedback.setText(state.userMessage() == null ? "Готово." : state.userMessage());
            displayStatus.setText(target == null ? "Внешний экран не выбран" : "Внешний экран выбран");
            topologyStatus.setText(target == null ? "Ожидание HDMI" : topologyLabel(target));

            PlaybackUiState playback = state.playback();
            boolean activePlayback = state.outputState() == OutputSessionState.OUTPUT_ACTIVE
                    && playback.controlsAvailable();
            boolean canStopOutput = state.outputState() == OutputSessionState.OUTPUT_IDLE
                    || state.outputState() == OutputSessionState.OUTPUT_ACTIVE;
            startOutputButton.setDisable(!state.readyForOutput() || state.outputState() != OutputSessionState.TARGET_READY);
            pauseButton.setDisable(!activePlayback);
            pauseButton.setText(playback.playerState() == PlayerState.PAUSED ? "Продолжить" : "Пауза");
            stopPlaybackButton.setDisable(!activePlayback);
            seekBackwardButton.setDisable(!activePlayback);
            seekForwardButton.setDisable(!activePlayback);
            stopOutputButton.setDisable(!canStopOutput);

            playbackTime.setText(timeLabel(playback.position()) + " / "
                    + playback.duration().map(MainViewController::timeLabel).orElse("--:--"));
            progressSlider.setDisable(!activePlayback || playback.duration().isEmpty());
            if (!progressSlider.isValueChanging()) {
                progressSlider.setMin(0);
                progressSlider.setMax(playback.duration().map(Duration::toSeconds).orElse(0L));
                progressSlider.setValue(Math.min(progressSlider.getMax(), playback.position().toMillis() / 1_000.0));
            }

            volumeSlider.setDisable(!activePlayback);
            if (!volumeSlider.isValueChanging()) {
                volumeSlider.setValue(playback.volumePercent());
            }
            fitButton.setDisable(!activePlayback);
            fillButton.setDisable(!activePlayback);
            oneToOneButton.setDisable(!activePlayback);
            setSelectedScaling(playback.scalingMode());

            List<AudioOutputDevice> audioOutputs = playback.audioOutputs();
            audioOutputSelector.setItems(FXCollections.observableArrayList(audioOutputs));
            audioOutputSelector.setValue(audioOutputs.stream()
                    .filter(device -> device.id().equals(playback.selectedAudioOutputId()))
                    .findFirst().orElse(null));
            audioOutputSelector.setDisable(!activePlayback || audioOutputs.isEmpty());

            List<MediaTrack> audioTracks = playback.tracks().stream()
                    .filter(track -> track.kind() == MediaTrackKind.AUDIO)
                    .toList();
            audioTrackSelector.setItems(FXCollections.observableArrayList(audioTracks));
            audioTrackSelector.setValue(audioTracks.stream().filter(MediaTrack::selected).findFirst().orElse(null));
            audioTrackSelector.setDisable(!activePlayback || audioTracks.isEmpty());
        } finally {
            applyingState = false;
        }
    }

    private void changeVolumeBy(int delta) {
        int current = store.current().playback().volumePercent();
        service.setVolume(Math.clamp(current + delta, 0, 100));
    }

    private void setSelectedScaling(ScalingMode scalingMode) {
        fitButton.getStyleClass().remove("selected-toggle-button");
        fillButton.getStyleClass().remove("selected-toggle-button");
        oneToOneButton.getStyleClass().remove("selected-toggle-button");
        switch (scalingMode) {
            case FIT -> fitButton.getStyleClass().add("selected-toggle-button");
            case FILL -> fillButton.getStyleClass().add("selected-toggle-button");
            case ONE_TO_ONE -> oneToOneButton.getStyleClass().add("selected-toggle-button");
        }
    }

    private static String timeLabel(Duration value) {
        long seconds = Math.max(0, value.toSeconds());
        return "%02d:%02d".formatted(seconds / 60, seconds % 60);
    }

    private static String fileName(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    private static String topologyLabel(DisplayInfo target) {
        String connection = target.connectionType().name().replace('_', ' ');
        return target.friendlyName() + " · " + connection;
    }

    private static String modeLabel(DisplayMode mode) {
        return mode.width() + " × " + mode.height() + " · " + String.format(java.util.Locale.ROOT, "%.3f", mode.refreshRate().hertz()) + " Гц";
    }

    private boolean confirmDisplayPreparation(DisplayInfo target) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.initOwner(stage);
        confirmation.setTitle("Подготовить внешний экран");
        confirmation.setHeaderText("Видео будет выведено только на «" + target.friendlyName() + "».");
        confirmation.setContentText("ScreenPilot сохранит текущую конфигурацию Windows и при необходимости временно включит расширенный режим. "
                + "После остановки вывода исходная конфигурация будет восстановлена.");
        return confirmation.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private void showError(String header, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle("ScreenPilot");
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private static final class DisplayCell extends ListCell<DisplayInfo> {
        @Override
        protected void updateItem(DisplayInfo item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.friendlyName() + " (" + item.connectionType() + ")");
        }
    }

    private static final class ModeCell extends ListCell<DisplayMode> {
        @Override
        protected void updateItem(DisplayMode item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : modeLabel(item));
        }
    }

    private static final class AudioOutputCell extends ListCell<AudioOutputDevice> {
        @Override
        protected void updateItem(AudioOutputDevice item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.description());
        }
    }

    private static final class AudioTrackCell extends ListCell<MediaTrack> {
        @Override
        protected void updateItem(MediaTrack item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                return;
            }
            String language = item.language().isBlank() ? "без языка" : item.language();
            String title = item.title().isBlank() ? item.codec() : item.title();
            setText("Дорожка " + item.id() + ": " + language + (title.isBlank() ? "" : " · " + title));
        }
    }
}
