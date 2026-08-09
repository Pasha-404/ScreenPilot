package ru.pavelkuzmin.screenpilot.domain.media;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable ordered playlist. Duplicate detection uses the local-file fingerprint, never only a name. */
public record Playlist(List<PlaylistItem> items, int selectedIndex) {
    public Playlist {
        items = List.copyOf(Objects.requireNonNullElse(items, List.of()));
        if (items.isEmpty() && selectedIndex != -1) {
            throw new IllegalArgumentException("An empty playlist must not have a selected row");
        }
        if (!items.isEmpty() && (selectedIndex < 0 || selectedIndex >= items.size())) {
            throw new IllegalArgumentException("selectedIndex must reference a playlist item");
        }
        long uniqueFingerprints = items.stream().map(PlaylistItem::fingerprint).distinct().count();
        if (uniqueFingerprints != items.size()) {
            throw new IllegalArgumentException("Playlist must not contain duplicate media fingerprints");
        }
    }

    public static Playlist empty() {
        return new Playlist(List.of(), -1);
    }

    public Optional<PlaylistItem> selectedItem() {
        return selectedIndex < 0 ? Optional.empty() : Optional.of(items.get(selectedIndex));
    }

    public PlaylistMutation addOrSelect(PlaylistItem item) {
        Objects.requireNonNull(item, "item");
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).fingerprint().equals(item.fingerprint())) {
                return new PlaylistMutation(new Playlist(items, index), false, index);
            }
        }
        List<PlaylistItem> expanded = new ArrayList<>(items);
        expanded.add(item);
        return new PlaylistMutation(new Playlist(expanded, expanded.size() - 1), true, expanded.size() - 1);
    }

    public Playlist select(int index) {
        if (index < 0 || index >= items.size()) {
            throw new IllegalArgumentException("Playlist index is out of bounds: " + index);
        }
        return new Playlist(items, index);
    }

    public Playlist removeSelected() {
        if (selectedIndex < 0) {
            return this;
        }
        List<PlaylistItem> reduced = new ArrayList<>(items);
        reduced.remove(selectedIndex);
        return reduced.isEmpty() ? empty() : new Playlist(reduced, Math.min(selectedIndex, reduced.size() - 1));
    }

    public Playlist moveSelectedBy(int offset) {
        if (selectedIndex < 0 || offset == 0) {
            return this;
        }
        int destination = selectedIndex + offset;
        if (destination < 0 || destination >= items.size()) {
            return this;
        }
        List<PlaylistItem> reordered = new ArrayList<>(items);
        PlaylistItem moved = reordered.remove(selectedIndex);
        reordered.add(destination, moved);
        return new Playlist(reordered, destination);
    }

    public Playlist move(int sourceIndex, int destinationIndex) {
        if (sourceIndex < 0 || sourceIndex >= items.size()
                || destinationIndex < 0 || destinationIndex >= items.size()
                || sourceIndex == destinationIndex) {
            return this;
        }
        List<PlaylistItem> reordered = new ArrayList<>(items);
        PlaylistItem moved = reordered.remove(sourceIndex);
        reordered.add(destinationIndex, moved);
        int nextSelection = selectedIndex;
        if (selectedIndex == sourceIndex) {
            nextSelection = destinationIndex;
        } else if (sourceIndex < selectedIndex && destinationIndex >= selectedIndex) {
            nextSelection--;
        } else if (sourceIndex > selectedIndex && destinationIndex <= selectedIndex) {
            nextSelection++;
        }
        return new Playlist(reordered, nextSelection);
    }

    public Optional<PlaylistItem> nextItem() {
        return selectedIndex >= 0 && selectedIndex + 1 < items.size()
                ? Optional.of(items.get(selectedIndex + 1))
                : Optional.empty();
    }

    public Optional<PlaylistItem> previousItem() {
        return selectedIndex > 0 ? Optional.of(items.get(selectedIndex - 1)) : Optional.empty();
    }

    public Playlist replace(PlaylistItem replacement) {
        Objects.requireNonNull(replacement, "replacement");
        int index = indexOf(replacement.fingerprint());
        if (index < 0) {
            return this;
        }
        List<PlaylistItem> changed = new ArrayList<>(items);
        changed.set(index, replacement);
        return new Playlist(changed, selectedIndex);
    }

    public Duration knownTotalDuration() {
        return items.stream()
                .flatMap(item -> item.mediaInfo().stream())
                .flatMap(info -> info.duration().stream())
                .reduce(Duration.ZERO, Duration::plus);
    }

    private int indexOf(MediaFingerprint fingerprint) {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).fingerprint().equals(fingerprint)) {
                return index;
            }
        }
        return -1;
    }
}
