package ru.pavelkuzmin.screenpilot.domain.port;

import java.io.IOException;
import java.util.Objects;

/** Optional OS-specific guard for a child process such as a Windows Job Object. */
@FunctionalInterface
public interface ProcessContainment {
    Handle attach(Process process) throws IOException;

    interface Handle extends AutoCloseable {
        @Override
        void close();

        static Handle none() {
            return () -> {
            };
        }
    }

    static ProcessContainment disabled() {
        return process -> {
            Objects.requireNonNull(process, "process");
            return Handle.none();
        };
    }
}
