package ru.pavelkuzmin.screenpilot.domain.media;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PlaylistTest {

    @Test
    void selectsExistingRowInsteadOfAddingTheSameFingerprintTwice() {
        PlaylistItem first = item("C:/Video/first.mkv", 1);
        Playlist playlist = Playlist.empty().addOrSelect(first).playlist();

        PlaylistMutation duplicate = playlist.addOrSelect(first);

        assertThat(duplicate.added()).isFalse();
        assertThat(duplicate.playlist().items()).containsExactly(first);
        assertThat(duplicate.selectedIndex()).isZero();
    }

    @Test
    void preservesSelectionWhenMovingRowsAndNeverWrapsNavigation() {
        PlaylistItem first = item("C:/Video/first.mkv", 1);
        PlaylistItem second = item("C:/Video/second.mkv", 2);
        Playlist playlist = Playlist.empty().addOrSelect(first).playlist().addOrSelect(second).playlist();

        Playlist moved = playlist.moveSelectedBy(-1);

        assertThat(moved.items()).containsExactly(second, first);
        assertThat(moved.selectedItem()).contains(second);
        assertThat(moved.previousItem()).isEmpty();
        assertThat(moved.nextItem()).contains(first);
    }

    @Test
    void removesSelectedRowAndSelectsTheClosestRemainingOne() {
        PlaylistItem first = item("C:/Video/first.mkv", 1);
        PlaylistItem second = item("C:/Video/second.mkv", 2);
        Playlist playlist = Playlist.empty().addOrSelect(first).playlist().addOrSelect(second).playlist();

        Playlist remaining = playlist.removeSelected();

        assertThat(remaining.items()).containsExactly(first);
        assertThat(remaining.selectedItem()).contains(first);
    }

    private static PlaylistItem item(String path, long size) {
        Path source = Path.of(path).toAbsolutePath().normalize();
        return PlaylistItem.pending(new MediaFingerprint(source.toString(), size, Instant.parse("2026-08-09T12:00:00Z")));
    }
}
