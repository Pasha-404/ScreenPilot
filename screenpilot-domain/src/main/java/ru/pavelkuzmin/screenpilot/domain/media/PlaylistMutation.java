package ru.pavelkuzmin.screenpilot.domain.media;

import java.util.Objects;

/** Result of adding a file, so the UI can distinguish a new row from a selected duplicate. */
public record PlaylistMutation(Playlist playlist, boolean added, int selectedIndex) {
    public PlaylistMutation {
        playlist = Objects.requireNonNull(playlist, "playlist");
        if (selectedIndex < 0 || selectedIndex >= playlist.items().size()) {
            throw new IllegalArgumentException("selectedIndex must reference a playlist item");
        }
    }
}
