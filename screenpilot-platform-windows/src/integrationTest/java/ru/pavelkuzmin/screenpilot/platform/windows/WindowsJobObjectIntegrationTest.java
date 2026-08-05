package ru.pavelkuzmin.screenpilot.platform.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledOnOs(OS.WINDOWS)
class WindowsJobObjectIntegrationTest {

    @Test
    void closingJobTerminatesAssignedChildProcess() throws Exception {
        String commandInterpreter = Path.of(System.getenv("ComSpec")).toString();
        Process child = new ProcessBuilder(commandInterpreter, "/d", "/c", "timeout /t 60 /nobreak > NUL")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            try (WindowsJobObject job = WindowsJobObject.create()) {
                try {
                    job.assign(child);
                } catch (java.io.IOException exception) {
                    Assumptions.assumeTrue(false, () -> "The current test host already owns the child in a "
                            + "Windows Job Object and prohibits reassignment: " + exception.getMessage());
                }
                assertThat(child.isAlive()).isTrue();
            }

            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(child.isAlive()).isFalse();
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
