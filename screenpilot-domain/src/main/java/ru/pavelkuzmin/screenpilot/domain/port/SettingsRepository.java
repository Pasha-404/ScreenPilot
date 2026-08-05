package ru.pavelkuzmin.screenpilot.domain.port;

import ru.pavelkuzmin.screenpilot.domain.settings.AppSettings;

public interface SettingsRepository {
    AppSettings load();

    void save(AppSettings settings);
}
