package ru.pavelkuzmin.screenpilot.app.ui;

import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Produces support data without exposing local media paths or user-profile names. */
final class DiagnosticReport {

    /** Redact from a drive/UNC prefix through the log line: privacy is worth more than its free text suffix. */
    private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)(?:[a-z]:[\\\\/]|\\\\\\\\)[^\\r\\n]*");
    private static final Pattern ERROR_CODE = Pattern.compile("\\b(?:DSP|PLY|AUD|SUB|CFG|APP)-\\d{3}\\b");

    private DiagnosticReport() {
    }

    static String render(ApplicationState state, List<String> recentLogLines) {
        StringBuilder report = new StringBuilder("ScreenPilot — диагностика\n");
        report.append("Версия: ").append(System.getProperty("screenpilot.version", "dev")).append('\n');
        report.append("ОС: ").append(System.getProperty("os.name", "unknown")).append(' ')
                .append(System.getProperty("os.version", "unknown")).append('\n');
        report.append("Java: ").append(System.getProperty("java.runtime.version", "unknown")).append('\n');
        report.append("Состояние вывода: ").append(state.outputState()).append('\n');
        report.append("Состояние плеера: ").append(state.playback().playerState()).append('\n');
        report.append("Файл: ").append(state.selectedMedia() == null ? "не выбран" : state.selectedMedia().getFileName()).append('\n');
        report.append("Позиция: ").append(formatDuration(state.playback().position())).append('\n');
        report.append("Выбранный экран: ").append(state.selectedTarget()
                .map(DiagnosticReport::displaySummary).orElse("не выбран")).append('\n');
        report.append("Экраны:\n");
        if (state.displays().isEmpty()) {
            report.append("- не обнаружены\n");
        } else {
            for (DisplayInfo display : state.displays()) {
                report.append("- ").append(displaySummary(display)).append('\n');
            }
        }

        List<String> safeLogLines = recentLogLines == null ? List.of() : recentLogLines.stream()
                .map(DiagnosticReport::redact)
                .toList();
        Set<String> errorCodes = new LinkedHashSet<>();
        addErrorCodes(errorCodes, state.userMessage());
        safeLogLines.forEach(line -> addErrorCodes(errorCodes, line));
        report.append("Коды ошибок: ").append(errorCodes.isEmpty() ? "нет" : String.join(", ", errorCodes)).append('\n');
        report.append("Последние строки лога:\n");
        if (safeLogLines.isEmpty()) {
            report.append("- журнал пока пуст\n");
        } else {
            safeLogLines.forEach(line -> report.append(redact(line)).append('\n'));
        }
        return report.toString();
    }

    static List<String> lastLines(List<String> source, int limit) {
        ArrayDeque<String> tail = new ArrayDeque<>(Math.max(1, limit));
        for (String line : source == null ? List.<String>of() : source) {
            if (tail.size() == limit) {
                tail.removeFirst();
            }
            tail.addLast(line);
        }
        return List.copyOf(tail);
    }

    static String redact(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String result = WINDOWS_PATH.matcher(value).replaceAll("<локальный-путь>");
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            result = result.replace(home, "<домашний-каталог>");
        }
        return result;
    }

    private static void addErrorCodes(Set<String> codes, String value) {
        if (value == null) {
            return;
        }
        Matcher matcher = ERROR_CODE.matcher(value.toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            codes.add(matcher.group());
        }
    }

    private static String displaySummary(DisplayInfo display) {
        DisplayMode mode = display.currentMode();
        String modeLabel = mode == null
                ? "режим неизвестен"
                : mode.width() + "×" + mode.height() + " @ %.3f Гц".formatted(mode.refreshRate().hertz());
        return display.friendlyName() + " (" + display.connectionType() + ", "
                + (display.active() ? "активен" : "неактивен") + ", " + modeLabel + ")";
    }

    private static String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        return "%02d:%02d:%02d".formatted(seconds / 3_600, (seconds % 3_600) / 60, seconds % 60);
    }
}
