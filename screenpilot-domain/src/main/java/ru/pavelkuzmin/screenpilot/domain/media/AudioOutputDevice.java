package ru.pavelkuzmin.screenpilot.domain.media;

/** mpv-local output device. Selecting it must not change the Windows default endpoint. */
public record AudioOutputDevice(String id, String description) {
    public AudioOutputDevice {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("audio device id must not be blank");
        }
        description = description == null || description.isBlank() ? id : description.trim();
    }
}
