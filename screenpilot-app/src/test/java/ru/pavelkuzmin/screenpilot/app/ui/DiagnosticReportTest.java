package ru.pavelkuzmin.screenpilot.app.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticReportTest {

    @Test
    void reportRedactsWindowsPathsAndIncludesRecentErrorCodes() {
        String report = DiagnosticReport.render(ApplicationState.initial(), List.of(
                "2026-08-21 INFO opened C:\\Users\\Pavel\\Videos\\movie.mkv",
                "2026-08-21 WARN PLY-003 cannot open C:\\Video\\private.mkv"
        ));

        assertThat(report).contains("PLY-003", "Последние строки лога");
        assertThat(report).doesNotContain("C:\\Users\\Pavel", "C:\\Video\\private.mkv");
        assertThat(report).contains("<локальный-путь>");
        assertThat(report).doesNotContain("Users", "Pavel", "Videos", "private.mkv");
    }

    @Test
    void redactsUncPathsPathsWithSpacesAndJsonEscapedWindowsPaths() {
        String rawPath = DiagnosticReport.redact("PLY-003 C:\\Users\\Synthetic Person\\Private Videos\\movie.mkv reason");
        String uncPath = DiagnosticReport.redact("PLY-003 \\\\server\\private-share\\movie.mkv reason");
        String escapedPath = DiagnosticReport.redact("{\\\"path\\\":\\\"C:\\\\\\\\Users\\\\SyntheticPerson\\\\secret.mkv\\\"}");

        assertThat(rawPath).contains("PLY-003", "<локальный-путь>")
                .doesNotContain("Synthetic", "Private", "movie.mkv");
        assertThat(uncPath).contains("PLY-003", "<локальный-путь>")
                .doesNotContain("server", "private-share", "movie.mkv");
        assertThat(escapedPath).contains("<локальный-путь>")
                .doesNotContain("SyntheticPerson", "secret.mkv");
    }

    @Test
    void keepsOnlyTheRequestedTailOfLogLines() {
        assertThat(DiagnosticReport.lastLines(List.of("1", "2", "3"), 2)).containsExactly("2", "3");
    }
}
