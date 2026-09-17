package ru.pavelkuzmin.screenpilot.app.ui;

import ru.pavelkuzmin.screenpilot.domain.display.DisplayId;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;
import ru.pavelkuzmin.screenpilot.domain.media.Playlist;
import ru.pavelkuzmin.screenpilot.domain.media.PlaylistItem;
import ru.pavelkuzmin.screenpilot.domain.media.ResumeEntry;
import ru.pavelkuzmin.screenpilot.domain.recovery.RecoveryRecord;
import ru.pavelkuzmin.screenpilot.domain.session.OutputSessionState;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable UI projection; only the application service creates new snapshots. */
public record ApplicationState(
        List<DisplayInfo> displays,
        String selectedTargetId,
        DisplayMode selectedMode,
        Path selectedMedia,
        Playlist playlist,
        Path lastMediaFolder,
        ResumeEntry resumeOffer,
        Duration requestedStartPosition,
        OutputSessionState outputState,
        PlaybackUiState playback,
        String userMessage,
        boolean refreshingDisplays,
        RecoveryRecord pendingRecovery
) {

    /** Compatibility constructor for normal state projections without a pending recovery action. */
    public ApplicationState(
            List<DisplayInfo> displays,
            String selectedTargetId,
            DisplayMode selectedMode,
            Path selectedMedia,
            Playlist playlist,
            Path lastMediaFolder,
            ResumeEntry resumeOffer,
            Duration requestedStartPosition,
            OutputSessionState outputState,
            PlaybackUiState playback,
            String userMessage,
            boolean refreshingDisplays
    ) {
        this(displays, selectedTargetId, selectedMode, selectedMedia, playlist, lastMediaFolder, resumeOffer,
                requestedStartPosition, outputState, playback, userMessage, refreshingDisplays, null);
    }

    public ApplicationState {
        displays = List.copyOf(Objects.requireNonNullElse(displays, List.of()));
        selectedTargetId = optionalText(selectedTargetId);
        selectedMedia = selectedMedia == null ? null : selectedMedia.toAbsolutePath().normalize();
        playlist = playlist == null ? Playlist.empty() : playlist;
        lastMediaFolder = lastMediaFolder == null ? null : lastMediaFolder.toAbsolutePath().normalize();
        requestedStartPosition = requestedStartPosition == null || requestedStartPosition.isNegative()
                ? Duration.ZERO : requestedStartPosition;
        outputState = outputState == null ? OutputSessionState.NO_TARGET : outputState;
        playback = playback == null ? PlaybackUiState.idle() : playback;
        userMessage = optionalText(userMessage);
    }

    public static ApplicationState initial() {
        return new ApplicationState(List.of(), null, null, null, Playlist.empty(), null, null, Duration.ZERO,
                OutputSessionState.NO_TARGET, PlaybackUiState.idle(),
                "Ищу подключённые внешние экраны…", true);
    }

    public List<DisplayInfo> externalTargets() {
        return displays.stream()
                .filter(display -> !display.internal())
                .filter(display -> display.active() || display.targetAvailable())
                .toList();
    }

    public Optional<DisplayInfo> selectedTarget() {
        if (selectedTargetId == null) {
            return Optional.empty();
        }
        return displays.stream().filter(display -> display.id().value().equals(selectedTargetId)).findFirst();
    }

    public boolean readyForOutput() {
        return selectedMedia != null && resumeOffer == null && selectedTarget().isPresent()
                && !refreshingDisplays && pendingRecovery == null;
    }

    public Optional<PlaylistItem> selectedPlaylistItem() {
        return playlist.selectedItem();
    }

    ApplicationState withRefreshInProgress() {
        return new ApplicationState(displays, selectedTargetId, selectedMode, selectedMedia, playlist, lastMediaFolder,
                resumeOffer, requestedStartPosition, outputState, playback, userMessage, true, pendingRecovery);
    }

    ApplicationState withDisplays(List<DisplayInfo> discovered) {
        List<DisplayInfo> safeDisplays = List.copyOf(Objects.requireNonNullElse(discovered, List.of()));
        List<DisplayInfo> targets = safeDisplays.stream()
                .filter(display -> !display.internal())
                .filter(display -> display.active() || display.targetAvailable())
                .toList();
        String selected = targets.stream()
                .map(DisplayInfo::id)
                .map(DisplayId::value)
                .filter(id -> id.equals(selectedTargetId))
                .findFirst()
                .orElseGet(() -> targets.size() == 1 ? targets.getFirst().id().value() : null);
        DisplayMode mode = targets.stream()
                .filter(display -> display.id().value().equals(selected))
                .findFirst()
                .map(target -> retainSelectedMode(target, selectedMode))
                .orElse(null);
        OutputSessionState nextState = pendingRecovery != null ? OutputSessionState.OUTPUT_ERROR
                : selected == null ? OutputSessionState.NO_TARGET : OutputSessionState.TARGET_READY;
        String message = pendingRecovery != null
                ? recoveryMessage(pendingRecovery)
                : targets.isEmpty()
                ? "Подключите внешний экран по HDMI."
                : targets.size() == 1
                ? "Внешний экран подключён. Выберите видеофайл."
                : "Выберите внешний экран для вывода видео.";
        return new ApplicationState(safeDisplays, selected, mode, selectedMedia, playlist, lastMediaFolder,
                resumeOffer, requestedStartPosition, nextState, playback, message, false, pendingRecovery);
    }

    ApplicationState withSelectedTarget(DisplayInfo target) {
        Objects.requireNonNull(target, "target");
        boolean isKnownExternal = externalTargets().stream().anyMatch(display -> display.id().equals(target.id()));
        if (!isKnownExternal) {
            throw new IllegalArgumentException("Only a currently available external target can be selected");
        }
        OutputSessionState nextState = pendingRecovery == null ? OutputSessionState.TARGET_READY : OutputSessionState.OUTPUT_ERROR;
        String message = pendingRecovery == null ? "Выбран экран: " + target.friendlyName() + "." : recoveryMessage(pendingRecovery);
        return new ApplicationState(displays, target.id().value(), target.currentMode(), selectedMedia, playlist, lastMediaFolder,
                resumeOffer, requestedStartPosition, nextState, playback, message, false, pendingRecovery);
    }

    ApplicationState withSelectedMode(DisplayMode mode) {
        DisplayInfo target = selectedTarget().orElseThrow(
                () -> new IllegalStateException("A target must be selected before selecting its mode"));
        if (mode == null || !target.confirmedModes().contains(mode)) {
            throw new IllegalArgumentException("Only a confirmed mode of the selected target can be selected");
        }
        return new ApplicationState(displays, selectedTargetId, mode, selectedMedia, playlist, lastMediaFolder,
                resumeOffer, requestedStartPosition, outputState, playback,
                pendingRecovery == null ? "Выбран режим: " + mode.width() + " × " + mode.height() + "."
                        : recoveryMessage(pendingRecovery), false, pendingRecovery);
    }

    ApplicationState withSelectedMedia(Path media) {
        return new ApplicationState(displays, selectedTargetId, selectedMode, Objects.requireNonNull(media, "media"), playlist,
                lastMediaFolder, null, Duration.ZERO, outputState, playback,
                pendingRecovery == null ? "Файл выбран. Проверьте экран и параметры вывода." : recoveryMessage(pendingRecovery),
                false, pendingRecovery);
    }

    ApplicationState withPlaylist(Playlist nextPlaylist, ResumeEntry nextResumeOffer, String message) {
        Playlist safePlaylist = Objects.requireNonNull(nextPlaylist, "nextPlaylist");
        Path media = safePlaylist.selectedItem().map(PlaylistItem::source).orElse(null);
        return new ApplicationState(displays, selectedTargetId, selectedMode, media, safePlaylist, lastMediaFolder,
                nextResumeOffer, Duration.ZERO, outputState, playback,
                pendingRecovery == null ? message : recoveryMessage(pendingRecovery), false, pendingRecovery);
    }

    ApplicationState withResumeDecision(boolean resume) {
        if (resumeOffer == null) {
            return this;
        }
        Duration position = resume ? resumeOffer.position() : Duration.ZERO;
        String message = resume ? "Продолжу просмотр с сохранённой позиции." : "Видео начнётся с начала.";
        return new ApplicationState(displays, selectedTargetId, selectedMode, selectedMedia, playlist, lastMediaFolder,
                null, position, outputState, playback,
                pendingRecovery == null ? message : recoveryMessage(pendingRecovery), false, pendingRecovery);
    }

    ApplicationState withoutResumeOffer(String message) {
        return new ApplicationState(displays, selectedTargetId, selectedMode, selectedMedia, playlist, lastMediaFolder,
                null, Duration.ZERO, outputState, playback,
                pendingRecovery == null ? message : recoveryMessage(pendingRecovery), false, pendingRecovery);
    }

    ApplicationState withLastMediaFolder(Path folder) {
        return new ApplicationState(displays, selectedTargetId, selectedMode, selectedMedia, playlist,
                Objects.requireNonNull(folder, "folder"), resumeOffer, requestedStartPosition,
                outputState, playback, userMessage, false, pendingRecovery);
    }

    ApplicationState withProbedPlaylistItem(PlaylistItem item) {
        Playlist nextPlaylist = playlist.replace(Objects.requireNonNull(item, "item"));
        return new ApplicationState(displays, selectedTargetId, selectedMode, selectedMedia, nextPlaylist, lastMediaFolder,
                resumeOffer, requestedStartPosition, outputState, playback, userMessage, false, pendingRecovery);
    }

    ApplicationState withUserMessage(String message) {
        return new ApplicationState(displays, selectedTargetId, selectedMode, selectedMedia, playlist, lastMediaFolder,
                resumeOffer, requestedStartPosition, outputState, playback, message, false, pendingRecovery);
    }

    ApplicationState withOutputState(OutputSessionState state, String message) {
        return new ApplicationState(displays, selectedTargetId, selectedMode, selectedMedia, playlist, lastMediaFolder,
                resumeOffer, requestedStartPosition, state, playback, message, false, pendingRecovery);
    }

    ApplicationState withPendingRecovery(RecoveryRecord record) {
        RecoveryRecord safeRecord = Objects.requireNonNull(record, "record");
        return new ApplicationState(displays, selectedTargetId, selectedMode, selectedMedia, playlist, lastMediaFolder,
                resumeOffer, requestedStartPosition, OutputSessionState.OUTPUT_ERROR, playback,
                recoveryMessage(safeRecord), false, safeRecord);
    }

    ApplicationState withoutPendingRecovery(String message) {
        OutputSessionState nextState = selectedTarget().isPresent() ? OutputSessionState.TARGET_READY : OutputSessionState.NO_TARGET;
        return new ApplicationState(displays, selectedTargetId, selectedMode, selectedMedia, playlist, lastMediaFolder,
                resumeOffer, requestedStartPosition, nextState, playback, message, false, null);
    }

    ApplicationState withPlayback(PlaybackUiState nextPlayback) {
        return new ApplicationState(displays, selectedTargetId, selectedMode, selectedMedia, playlist, lastMediaFolder,
                resumeOffer, requestedStartPosition, outputState,
                Objects.requireNonNull(nextPlayback, "nextPlayback"), userMessage, false, pendingRecovery);
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static DisplayMode retainSelectedMode(DisplayInfo target, DisplayMode previousSelection) {
        if (previousSelection != null) {
            Optional<DisplayMode> confirmedSelection = target.confirmedModes().stream()
                    .filter(previousSelection::equals)
                    .findFirst();
            if (confirmedSelection.isPresent()) {
                return confirmedSelection.get();
            }
        }
        return target.currentMode();
    }

    private static String recoveryMessage(RecoveryRecord record) {
        return "Найдена незавершённая сессия изменения экранов от " + record.startedAt()
                + ". Выберите восстановление или подтвердите текущую конфигурацию.";
    }
}
