package ru.pavelkuzmin.screenpilot.app.ui;

import ru.pavelkuzmin.screenpilot.domain.display.DisplayId;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;
import ru.pavelkuzmin.screenpilot.domain.session.OutputSessionState;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable UI projection; only the application service creates new snapshots. */
public record ApplicationState(
        List<DisplayInfo> displays,
        String selectedTargetId,
        DisplayMode selectedMode,
        Path selectedMedia,
        OutputSessionState outputState,
        PlaybackUiState playback,
        String userMessage,
        boolean refreshingDisplays
) {

    public ApplicationState {
        displays = List.copyOf(Objects.requireNonNullElse(displays, List.of()));
        selectedTargetId = optionalText(selectedTargetId);
        selectedMedia = selectedMedia == null ? null : selectedMedia.toAbsolutePath().normalize();
        outputState = outputState == null ? OutputSessionState.NO_TARGET : outputState;
        playback = playback == null ? PlaybackUiState.idle() : playback;
        userMessage = optionalText(userMessage);
    }

    public static ApplicationState initial() {
        return new ApplicationState(List.of(), null, null, null, OutputSessionState.NO_TARGET, PlaybackUiState.idle(),
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
        return selectedMedia != null && selectedTarget().isPresent() && !refreshingDisplays;
    }

    ApplicationState withRefreshInProgress() {
        return new ApplicationState(displays, selectedTargetId, selectedMode, selectedMedia, outputState, playback, userMessage, true);
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
                .map(DisplayInfo::currentMode)
                .orElse(null);
        OutputSessionState nextState = selected == null ? OutputSessionState.NO_TARGET : OutputSessionState.TARGET_READY;
        String message = targets.isEmpty()
                ? "Подключите внешний экран по HDMI."
                : targets.size() == 1
                ? "Внешний экран подключён. Выберите видеофайл."
                : "Выберите внешний экран для вывода видео.";
        return new ApplicationState(safeDisplays, selected, mode, selectedMedia, nextState, playback, message, false);
    }

    ApplicationState withSelectedTarget(DisplayInfo target) {
        Objects.requireNonNull(target, "target");
        boolean isKnownExternal = externalTargets().stream().anyMatch(display -> display.id().equals(target.id()));
        if (!isKnownExternal) {
            throw new IllegalArgumentException("Only a currently available external target can be selected");
        }
        return new ApplicationState(displays, target.id().value(), target.currentMode(), selectedMedia, OutputSessionState.TARGET_READY, playback,
                "Выбран экран: " + target.friendlyName() + ".", false);
    }

    ApplicationState withSelectedMode(DisplayMode mode) {
        DisplayInfo target = selectedTarget().orElseThrow(
                () -> new IllegalStateException("A target must be selected before selecting its mode"));
        if (mode == null || !target.confirmedModes().contains(mode)) {
            throw new IllegalArgumentException("Only a confirmed mode of the selected target can be selected");
        }
        return new ApplicationState(displays, selectedTargetId, mode, selectedMedia, outputState, playback,
                "Выбран режим: " + mode.width() + " × " + mode.height() + ".", false);
    }

    ApplicationState withSelectedMedia(Path media) {
        return new ApplicationState(displays, selectedTargetId, selectedMode, Objects.requireNonNull(media, "media"), outputState, playback,
                "Файл выбран. Проверьте экран и параметры вывода.", false);
    }

    ApplicationState withUserMessage(String message) {
        return new ApplicationState(displays, selectedTargetId, selectedMode, selectedMedia, outputState, playback, message, false);
    }

    ApplicationState withOutputState(OutputSessionState state, String message) {
        return new ApplicationState(displays, selectedTargetId, selectedMode, selectedMedia, state, playback, message, false);
    }

    ApplicationState withPlayback(PlaybackUiState nextPlayback) {
        return new ApplicationState(displays, selectedTargetId, selectedMode, selectedMedia, outputState,
                Objects.requireNonNull(nextPlayback, "nextPlayback"), userMessage, false);
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
