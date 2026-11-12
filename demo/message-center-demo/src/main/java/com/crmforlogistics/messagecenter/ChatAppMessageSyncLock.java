package com.crmforlogistics.messagecenter;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

final class ChatAppMessageSyncLock {
    private ChatAppMessageSyncLock() {
    }

    static Optional<Handle> tryAcquire(Path messageFile) throws IOException {
        Path target = messageFile.toAbsolutePath().normalize();
        Path lockFile = target.resolveSibling(target.getFileName() + ".sync.lock");
        Files.createDirectories(lockFile.getParent());
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            return Optional.of(new Handle(channel, lock));
        } catch (OverlappingFileLockException exception) {
            channel.close();
            return Optional.empty();
        } catch (IOException | RuntimeException exception) {
            channel.close();
            throw exception;
        }
    }

    static final class Handle implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;

        private Handle(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                lock.release();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
