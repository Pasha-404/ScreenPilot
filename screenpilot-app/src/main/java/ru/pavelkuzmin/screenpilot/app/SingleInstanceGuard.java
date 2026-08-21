package ru.pavelkuzmin.screenpilot.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Process-wide guard backed by the user profile; a non-owner must never mutate displays. */
public final class SingleInstanceGuard implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private SingleInstanceGuard(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static SingleInstanceGuard acquire(Path applicationDataDirectory) throws IOException {
        Files.createDirectories(applicationDataDirectory);
        FileChannel channel = FileChannel.open(applicationDataDirectory.resolve("screenpilot.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            return new SingleInstanceGuard(channel, channel.tryLock());
        } catch (OverlappingFileLockException exception) {
            return new SingleInstanceGuard(channel, null);
        } catch (IOException | RuntimeException exception) {
            channel.close();
            throw exception;
        }
    }

    public boolean isOwner() {
        return lock != null && lock.isValid();
    }

    @Override
    public void close() throws IOException {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } finally {
            channel.close();
        }
    }
}
