package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChatAppHistoryStoreTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final long FILE_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(25);

    @TempDir
    Path tempDir;

    @Test
    void twoStoreInstancesDoNotLoseConcurrentInserts() throws Exception {
        Path messages = tempDir.resolve("messages.jsonl");
        Config config = config(messages);
        CountDownLatch firstAtCommit = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        CountDownLatch secondAtCommit = new CountDownLatch(1);
        ChatAppHistoryStore first = new ChatAppHistoryStore(config, (source, target) -> {
            firstAtCommit.countDown();
            awaitLatch(allowFirstCommit, "first commit was not released");
            atomicMove(source, target);
        });
        ChatAppHistoryStore second = new ChatAppHistoryStore(config, (source, target) -> {
            secondAtCommit.countDown();
            atomicMove(source, target);
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ChatAppHistoryStore.WriteResult> one = pool.submit(() -> append(first, "one"));
            assertTrue(firstAtCommit.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            Future<ChatAppHistoryStore.WriteResult> two = pool.submit(() -> append(second, "two"));
            assertFalse(secondAtCommit.await(200, TimeUnit.MILLISECONDS),
                    "the second store must not enter its transaction commit while the first holds the JVM lock");
            assertFalse(two.isDone(), "the second store must still be waiting for the first transaction");
            allowFirstCommit.countDown();
            one.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            two.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            allowFirstCommit.countDown();
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        List<String> lines = Files.readAllLines(messages, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.stream().anyMatch(line -> line.contains("\"id\":\"one\"")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("\"id\":\"two\"")));
        assertNoTemporaryFiles(messages);
    }

    @Test
    void failedAtomicMoveLeavesExistingFileUnchanged() throws Exception {
        Path messages = tempDir.resolve("messages.jsonl");
        byte[] original = ("{\"id\":\"kept\",\"direction\":\"inbound\","
                + "\"timestamp\":\"2026-07-31T00:00:00Z\"}\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(messages, original);
        ChatAppHistoryStore store = new ChatAppHistoryStore(config(messages), (source, target) -> {
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
        });

        assertThrows(AtomicMoveNotSupportedException.class, () -> store.appendResult(
                "new", "inbound", "user", "business", "new", "Received",
                "2026-07-31T00:00:01Z", "{}", Map.of()));

        assertArrayEquals(original, Files.readAllBytes(messages));
        assertNoTemporaryFiles(messages);
    }

    @Test
    void waitsForCrossProcessLockAndRereadsBeforeCommit() throws Exception {
        Path messages = tempDir.resolve("messages.jsonl");
        Path ready = tempDir.resolve("ready");
        Path proceed = tempDir.resolve("proceed");
        Path childOutput = tempDir.resolve("lock-holder.log");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Process process = null;
        try {
            process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"),
                    LockHolder.class.getName(),
                    messages.toString(), ready.toString(), proceed.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(childOutput.toFile())
                    .start();
            awaitFile(ready);

            ChatAppHistoryStore store = new ChatAppHistoryStore(config(messages));
            CountDownLatch writeStarted = new CountDownLatch(1);
            Future<ChatAppHistoryStore.WriteResult> write = pool.submit(() -> {
                writeStarted.countDown();
                return store.appendResult(
                        "local", "inbound", "user", "business", "local", "Received",
                        "2026-07-31T00:00:01Z", "{}", Map.of());
            });
            assertTrue(writeStarted.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class,
                    () -> write.get(200, TimeUnit.MILLISECONDS),
                    "store write must block while the child JVM owns the file lock");

            Files.writeString(proceed, "proceed", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            assertTrue(process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), childDiagnostics(childOutput));
            assertEquals(0, process.exitValue(), childDiagnostics(childOutput));
            assertEquals(ChatAppHistoryStore.WriteResult.INSERTED,
                    write.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

            List<String> lines = Files.readAllLines(messages, StandardCharsets.UTF_8);
            assertEquals(2, lines.size());
            assertTrue(lines.stream().anyMatch(line -> line.contains("\"id\":\"external\"")));
            assertTrue(lines.stream().anyMatch(line -> line.contains("\"id\":\"local\"")));
            assertNoTemporaryFiles(messages);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
                if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                }
            }
            pool.shutdownNow();
            pool.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void duplicateIdReturnsSkippedOrUpdatedWithoutGrowingTheFile() throws Exception {
        Path messages = tempDir.resolve("messages.jsonl");
        ChatAppHistoryStore store = new ChatAppHistoryStore(config(messages));

        assertEquals(ChatAppHistoryStore.WriteResult.INSERTED, store.appendResult(
                "same", "inbound", "user", "business", "first", "Received",
                "2026-07-31T00:00:00Z", "{}", Map.of()));
        assertEquals(ChatAppHistoryStore.WriteResult.SKIPPED, store.appendResult(
                "same", "inbound", "user", "business", "first", "Received",
                "2026-07-31T00:00:00Z", "{}", Map.of()));
        assertEquals(ChatAppHistoryStore.WriteResult.UPDATED, store.appendResult(
                "same", "inbound", "user", "business", "changed", "Read",
                "2026-07-31T00:00:01Z", "{}", Map.of()));

        List<String> lines = Files.readAllLines(messages, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"text\":\"changed\""));
        assertTrue(lines.get(0).contains("\"status\":\"Read\""));
        assertNoTemporaryFiles(messages);
    }

    private Config config(Path messages) {
        return Config.forTests(tempDir, tempDir.resolve("email"), messages,
                tempDir.resolve("templates.json"));
    }

    private static ChatAppHistoryStore.WriteResult append(ChatAppHistoryStore store, String id) throws Exception {
        return store.appendResult(id, "inbound", id, "business", id, "Received",
                "2026-07-31T00:00:00Z", "{}", Map.of());
    }

    private static void awaitLatch(CountDownLatch latch, String message) throws IOException {
        try {
            if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for test coordination", exception);
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void assertNoTemporaryFiles(Path messages) throws IOException {
        try (var paths = Files.list(messages.getParent())) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString()
                    .startsWith(messages.getFileName() + ".")
                    && path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private static void awaitFile(Path marker) throws Exception {
        if (Files.exists(marker)) {
            return;
        }
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            marker.getParent().register(watcher, ENTRY_CREATE);
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (!Files.exists(marker)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException("timed out waiting for " + marker);
                }
                WatchKey key = watcher.poll(Math.min(remaining, FILE_POLL_NANOS), TimeUnit.NANOSECONDS);
                if (key == null) {
                    continue;
                }
                key.pollEvents();
                if (!key.reset() && !Files.exists(marker)) {
                    throw new IOException("watch service became invalid while waiting for " + marker);
                }
            }
        }
    }

    private static String childDiagnostics(Path output) {
        try {
            return Files.exists(output) ? Files.readString(output, StandardCharsets.UTF_8) : "no child output";
        } catch (IOException exception) {
            return "unable to read child output: " + exception.getMessage();
        }
    }

    public static final class LockHolder {
        private LockHolder() {
        }

        public static void main(String[] args) throws Exception {
            Path messages = Path.of(args[0]).toAbsolutePath().normalize();
            Path ready = Path.of(args[1]);
            Path proceed = Path.of(args[2]);
            Path lockFile = messages.resolveSibling(messages.getFileName() + ".lock");
            Files.createDirectories(messages.getParent());
            try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                proceed.getParent().register(watcher, ENTRY_CREATE);
                try (FileChannel lockChannel = FileChannel.open(lockFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock ignored = lockChannel.lock()) {
                    Files.writeString(ready, "ready", StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    awaitMarker(proceed, watcher);
                    writeExternalRecord(messages);
                }
            }
        }

        private static void awaitMarker(Path marker, WatchService watcher) throws Exception {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (!Files.exists(marker)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException("timed out waiting for " + marker);
                }
                WatchKey key = watcher.poll(Math.min(remaining, FILE_POLL_NANOS), TimeUnit.NANOSECONDS);
                if (key == null) {
                    continue;
                }
                key.pollEvents();
                if (!key.reset() && !Files.exists(marker)) {
                    throw new IOException("watch service became invalid while waiting for " + marker);
                }
            }
        }

        private static void writeExternalRecord(Path messages) throws Exception {
            Path temp = Files.createTempFile(messages.getParent(), messages.getFileName() + ".", ".tmp");
            try {
                byte[] bytes = ("{\"id\":\"external\",\"direction\":\"inbound\","
                        + "\"timestamp\":\"2026-07-31T00:00:00Z\"}"
                        + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
                try (FileChannel output = FileChannel.open(temp,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) {
                        output.write(buffer);
                    }
                    output.force(true);
                }
                Files.move(temp, messages,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }
}
