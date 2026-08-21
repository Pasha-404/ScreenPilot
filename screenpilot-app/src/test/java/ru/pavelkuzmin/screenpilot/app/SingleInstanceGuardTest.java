package ru.pavelkuzmin.screenpilot.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SingleInstanceGuardTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void onlyOneGuardCanOwnTheApplicationLockAtATime() throws Exception {
        try (SingleInstanceGuard first = SingleInstanceGuard.acquire(temporaryDirectory);
             SingleInstanceGuard second = SingleInstanceGuard.acquire(temporaryDirectory)) {
            assertThat(first.isOwner()).isTrue();
            assertThat(second.isOwner()).isFalse();
        }

        try (SingleInstanceGuard replacement = SingleInstanceGuard.acquire(temporaryDirectory)) {
            assertThat(replacement.isOwner()).isTrue();
        }
    }
}
