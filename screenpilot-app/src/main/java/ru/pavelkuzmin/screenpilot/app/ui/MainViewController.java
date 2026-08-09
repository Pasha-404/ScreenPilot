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
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.stage.Screen;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;
import ru.pavelkuzmin.screenpilot.domain.media.AudioOutputDevice;
import ru.pavelkuzmin.screenpilot.domain.media.MediaTrack;
import ru.pavelkuzmin.screenpilot.domain.media.MediaTrackKind;
import ru.pavelkuzmin.screenpilot.domain.media.PlaylistItem;
import ru.pavelkuzmin.screenpilot.domain.media.ResumeEntry;
import ru.pavelkuzmin.screenpilot.domain.session.OutputSessionState;
import ru.pavelkuzmin.screenpilot.domain.session.PlayerState;
import ru.pavelkuzmin.screenpilot.domain.settings.ScalingMode;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
    private ComboBox<SubtitleChoice> subtitleSelector;
    @FXML
    private ListView<PlaylistItem> playlistView;
    @FXML
    private Label playlistSummary;
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
    private Button addPlaylistButton;
    @FXML
    private Button removePlaylistButton;
    @FXML
    private Button movePlaylistUpButton;
    @FXML
    private Button movePlaylistDownButton;
    @FXML
    private Button addSubtitleButton;
    @FXML
    private Button previousButton;
    @FXML
    private Button nextButton;
    @FXML
    private Slider progressSlider;
    @FXML
    private Slider volumeSlider;
    private ResumeEntry lastShownResumeOffer;

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
        subtitleSelector.setCellFactory(ignored -> new SubtitleCell());
        subtitleSelector.setButtonCell(new SubtitleCell());
        playlistView.setCellFactory(ignored -> new PlaylistCell());
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
        subtitleSelector.valueProperty().addListener((ignored, previous, selected) -> {
            if (!applyingState && selected != null && selected != previous) {
                service.selectSubtitle(selected.trackId());
            }
        });
        playlistView.getSelectionModel().selectedIndexProperty().addListener((ignored, previous, selected) -> {
            if (!applyingState && selected != null && selected.intValue() >= 0) {
                service.selectPlaylistItem(selected.intValue());
            }
        });
        playlistView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && playlistView.getSelectionModel().getSelectedIndex() >= 0) {
                service.playSelectedPlaylistItem();
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
        setInitialDirectory(chooser, store.current().lastMediaFolder());
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null && !files.isEmpty()) {
            service.addMediaFiles(files.stream().map(File::toPath).toList());
        }
    }

    @FXML
    private void removePlaylistItem() {
        service.removeSelectedPlaylistItem();
    }

    @FXML
    private void movePlaylistUp() {
        service.moveSelectedPlaylistItem(-1);
    }

    @FXML
    private void movePlaylistDown() {
        service.moveSelectedPlaylistItem(1);
    }

    @FXML
    private void addSubtitle() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выберите файл субтитров");
        chooser.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter("Субтитры", "*.srt", "*.ass", "*.ssa"),
                new FileChooser.ExtensionFilter("Все файлы", "*.*")
        );
        Path initial = store.current().selectedMedia() == null
                ? store.current().lastMediaFolder()
                : store.current().selectedMedia().getParent();
        setInitialDirectory(chooser, initial);
        File file = chooser.showOpenDialog(stage);
        if (file != null) {
            service.addExternalSubtitle(file.toPath());
        }
    }

    @FXML
    private void previousPlaylistItem() {
        service.playRelativePlaylistItem(-1);
    }

    @FXML
    private void nextPlaylistItem() {
        service.playRelativePlaylistItem(1);
    }

    @FXML
    private void acceptVideoDrop(DragEvent event) {
        Dragboard dragboard = event.getDragboard();
        if (dragboard.hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    @FXML
    private void dropMediaFiles(DragEvent event) {
        Dragboard dragboard = event.getDragboard();
        boolean accepted = dragboard.hasFiles();
        if (accepted) {
            service.addMediaFiles(dragboard.getFiles().stream().map(File::toPath).toList());
        }
        event.setDropCompleted(accepted);
        event.consume();
    }

    @FXML
    private void refreshDisplays() {
        service.refreshDisplays();
    }

    @FXML
    private void startOutput() {
        ApplicationState state = store.current();
        if (state.outputState() == OutputSessionState.OUTPUT_IDLE) {
            service.startPlayback();
            return;
        }
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
            openMediaButton.setDisable(state.outputState() == OutputSessionState.PREPARING_DISPLAY
                    || state.outputState() == OutputSessionState.OUTPUT_ACTIVE
                    || state.outputState() == OutputSessionState.RESTORING_DISPLAY);
            refreshDisplaysButton.setDisable(outputChanging || state.refreshingDisplays());
            targetSelector.setDisable(state.refreshingDisplays() || targets.isEmpty() || outputChanging);
            targetSelector.setValue(state.selectedTarget().orElse(null));

            DisplayInfo target = state.selectedTarget().orElse(null);
            List<DisplayMode> modes = modeOptions(target);
            modeSelector.setItems(FXCollections.observableArrayList(modes));
            modeSelector.setValue(modes.stream()
                    .filter(mode -> mode.equals(state.selectedMode()))
                    .findFirst()
                    .orElse(null));
            modeSelector.setDisable(target == null || modes.isEmpty()
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
            boolean canStartNewOutput = state.readyForOutput() && state.outputState() == OutputSessionState.TARGET_READY;
            boolean canLoadIntoPreparedOutput = state.selectedMedia() != null
                    && state.outputState() == OutputSessionState.OUTPUT_IDLE;
            startOutputButton.setDisable(!canStartNewOutput && !canLoadIntoPreparedOutput);
            startOutputButton.setText(canLoadIntoPreparedOutput
                    ? "Воспроизвести"
                    : "Воспроизвести на внешнем экране");
            pauseButton.setDisable(!activePlayback);
            pauseButton.setText(playback.playerState() == PlayerState.PAUSED ? "Продолжить" : "Пауза");
            stopPlaybackButton.setDisable(!activePlayback);
            previousButton.setDisable(!activePlayback || state.playlist().previousItem().isEmpty());
            nextButton.setDisable(!activePlayback || state.playlist().nextItem().isEmpty());
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

            List<SubtitleChoice> subtitles = subtitleChoices(playback.tracks());
            subtitleSelector.setItems(FXCollections.observableArrayList(subtitles));
            subtitleSelector.setValue(subtitles.stream()
                    .filter(choice -> choice.trackId().isPresent()
                            && playback.tracks().stream().anyMatch(track -> track.id() == choice.trackId().getAsInt()
                            && track.selected()))
                    .findFirst()
                    .orElse(subtitles.getFirst()));
            subtitleSelector.setDisable(!activePlayback);
            addSubtitleButton.setDisable(!activePlayback);

            List<PlaylistItem> playlist = state.playlist().items();
            playlistView.setItems(FXCollections.observableArrayList(playlist));
            playlistView.getSelectionModel().select(state.playlist().selectedIndex());
            boolean playlistChangingDisabled = state.outputState() == OutputSessionState.PREPARING_DISPLAY
                    || state.outputState() == OutputSessionState.OUTPUT_ACTIVE
                    || state.outputState() == OutputSessionState.RESTORING_DISPLAY;
            playlistView.setDisable(playlistChangingDisabled);
            addPlaylistButton.setDisable(playlistChangingDisabled);
            boolean hasSelection = state.playlist().selectedItem().isPresent();
            removePlaylistButton.setDisable(playlistChangingDisabled || !hasSelection);
            movePlaylistUpButton.setDisable(playlistChangingDisabled || state.playlist().selectedIndex() <= 0);
            movePlaylistDownButton.setDisable(playlistChangingDisabled
                    || state.playlist().selectedIndex() < 0
                    || state.playlist().selectedIndex() >= playlist.size() - 1);
            playlistSummary.setText(playlist.isEmpty()
                    ? "Плейлист пуст"
                    : "Файлов: " + playlist.size() + " · известная длительность: "
                    + timeLabel(state.playlist().knownTotalDuration()));
        } finally {
            applyingState = false;
        }
        showResumeOfferIfNeeded(state);
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
        long hours = seconds / 3_600;
        long minutes = (seconds % 3_600) / 60;
        return hours > 0
                ? "%02d:%02d:%02d".formatted(hours, minutes, seconds % 60)
                : "%02d:%02d".formatted(minutes, seconds % 60);
    }

    private void showResumeOfferIfNeeded(ApplicationState state) {
        ResumeEntry offer = state.resumeOffer();
        if (offer == null) {
            lastShownResumeOffer = null;
            return;
        }
        if (stage == null || offer.equals(lastShownResumeOffer)) {
            return;
        }
        lastShownResumeOffer = offer;
        ButtonType resume = new ButtonType("Продолжить");
        ButtonType restart = new ButtonType("С начала");
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle("Продолжить просмотр?");
        alert.setHeaderText("Для этого видео сохранена позиция " + timeLabel(offer.position()) + ".");
        alert.setContentText("Выберите, продолжить просмотр или начать файл с начала.");
        alert.getButtonTypes().setAll(resume, restart);
        service.resolveResume(alert.showAndWait().filter(resume::equals).isPresent());
    }

    private static List<SubtitleChoice> subtitleChoices(List<MediaTrack> tracks) {
        List<SubtitleChoice> choices = new ArrayList<>();
        choices.add(new SubtitleChoice(java.util.OptionalInt.empty(), "Отключены"));
        tracks.stream().filter(track -> track.kind() == MediaTrackKind.SUBTITLE)
                .forEach(track -> choices.add(new SubtitleChoice(java.util.OptionalInt.of(track.id()), trackLabel(track))));
        return List.copyOf(choices);
    }

    private static String trackLabel(MediaTrack track) {
        String language = track.language().isBlank() ? "без языка" : track.language();
        String title = track.title().isBlank() ? track.codec() : track.title();
        return "Дорожка " + track.id() + ": " + language + (title.isBlank() ? "" : " · " + title);
    }

    private static void setInitialDirectory(FileChooser chooser, Path directory) {
        if (directory == null) {
            return;
        }
        try {
            chooser.setInitialDirectory(directory.toFile());
        } catch (IllegalArgumentException ignored) {
            // The folder can disappear after ScreenPilot has saved it; use the system default.
        }
    }

    private static String fileName(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    private static List<DisplayMode> modeOptions(DisplayInfo target) {
        if (target == null) {
            return List.of();
        }
        List<DisplayMode> modes = new ArrayList<>(target.confirmedModes());
        if (target.currentMode() != null && !modes.contains(target.currentMode())) {
            modes.addFirst(target.currentMode());
        }
        return List.copyOf(modes);
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

    private record SubtitleChoice(java.util.OptionalInt trackId, String label) {
        private SubtitleChoice {
            trackId = trackId == null ? java.util.OptionalInt.empty() : trackId;
            label = label == null || label.isBlank() ? "Отключены" : label;
        }
    }

    private static final class SubtitleCell extends ListCell<SubtitleChoice> {
        @Override
        protected void updateItem(SubtitleChoice item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.label());
        }
    }

    private final class PlaylistCell extends ListCell<PlaylistItem> {

        private PlaylistCell() {
            setOnDragDetected(event -> {
                if (getItem() == null || playlistView.isDisabled()) {
                    return;
                }
                Dragboard dragboard = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString("screenpilot-playlist:" + getIndex());
                dragboard.setContent(content);
                event.consume();
            });
            setOnDragOver(event -> {
                String text = event.getDragboard().hasString() ? event.getDragboard().getString() : "";
                if (!playlistView.isDisabled() && text.startsWith("screenpilot-playlist:")) {
                    event.acceptTransferModes(TransferMode.MOVE);
                }
                event.consume();
            });
            setOnDragDropped(event -> {
                String text = event.getDragboard().hasString() ? event.getDragboard().getString() : "";
                boolean accepted = false;
                if (!playlistView.isDisabled() && text.startsWith("screenpilot-playlist:")) {
                    try {
                        int source = Integer.parseInt(text.substring("screenpilot-playlist:".length()));
                        int destination = getIndex();
                        if (destination >= 0) {
                            service.movePlaylistItem(source, destination);
                            accepted = true;
                        }
                    } catch (NumberFormatException ignored) {
                        // A drag from another application is not an internal reorder request.
                    }
                }
                event.setDropCompleted(accepted);
                event.consume();
            });
        }

        @Override
        protected void updateItem(PlaylistItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setTooltip(null);
                return;
            }
            String details = item.mediaInfo().map(info -> {
                String resolution = info.width().isPresent() && info.height().isPresent()
                        ? info.width().getAsInt() + "×" + info.height().getAsInt()
                        : "—";
                String codec = info.videoCodec().orElse("—");
                String duration = info.duration().map(MainViewController::timeLabel).orElse("—");
                return resolution + " · " + codec + " · " + duration;
            }).orElseGet(() -> switch (item.probeStatus()) {
                case PENDING -> "Читаю параметры…";
                case FAILED -> "Не удалось прочитать параметры";
                case READY -> "—";
            });
            setText(item.displayName() + "\n" + details);
            setTooltip(new Tooltip(item.source().toString()));
        }
    }
}
