package ru.pavelkuzmin.screenpilot.platform.windows;

import ru.pavelkuzmin.screenpilot.domain.port.ProcessContainment;

import java.io.IOException;
import java.util.Objects;

/** Attaches a child process to a kill-on-close Windows Job Object. */
public final class WindowsProcessContainment implements ProcessContainment {

    @Override
    public Handle attach(Process process) throws IOException {
        Objects.requireNonNull(process, "process");
        WindowsJobObject job = WindowsJobObject.create();
        try {
            job.assign(process);
            return job::close;
        } catch (IOException exception) {
            job.close();
            throw exception;
        }
    }
}
