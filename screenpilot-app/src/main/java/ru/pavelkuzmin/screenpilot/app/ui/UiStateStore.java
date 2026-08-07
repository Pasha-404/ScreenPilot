package ru.pavelkuzmin.screenpilot.app.ui;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Thread-safe publisher of immutable UI snapshots. It deliberately has no JavaFX dependency. */
public final class UiStateStore {

    private final AtomicReference<ApplicationState> current = new AtomicReference<>(ApplicationState.initial());
    private final CopyOnWriteArrayList<Consumer<ApplicationState>> listeners = new CopyOnWriteArrayList<>();

    public ApplicationState current() {
        return current.get();
    }

    public AutoCloseable subscribe(Consumer<ApplicationState> listener) {
        listener = Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        listener.accept(current());
        Consumer<ApplicationState> registered = listener;
        return () -> listeners.remove(registered);
    }

    void publish(ApplicationState next) {
        current.set(Objects.requireNonNull(next, "next"));
        for (Consumer<ApplicationState> listener : listeners) {
            listener.accept(next);
        }
    }
}
