package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAudioStoreTest {
    private static final long MIN_AUDIO_LIMIT = 1_048_576L;
    private static final long MIN_STORAGE_LIMIT = 104_857_600L;
    private static final UUID CALL_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    @Test
    void stagesValidMp3ComputesHashAndPublishesServerOwnedPath() throws Exception {
        LocalAudioStore store = new LocalAudioStore(config(MIN_AUDIO_LIMIT, MIN_STORAGE_LIMIT, 7_200));
        byte[] expected = fixtureBytes("short-ding.mp3");

        LocalAudioStore.StagedAudio staged;
        try (InputStream input = new ByteArrayInputStream(expected)) {
            staged = store.stage(input, "../../call.mp3", "audio/mpeg");
        }
        assertTrue(Files.exists(staged.path()));
        assertEquals("call.mp3", staged.originalFileName());
        assertEquals(expected.length, staged.sizeBytes());
        assertEquals("42b9d70c9c51cfdff6ed60e874771049df657c93a0361220174582f07dceba53",
                staged.sha256());
        assertTrue(staged.durationSeconds() >= 0.05 && staged.durationSeconds() <= 0.2);

        AudioAsset asset = store.publish(CALL_ID, staged);
        assertEquals("audio/00000000-0000-0000-0000-000000000001.mp3", asset.relativePath());
        assertEquals("call.mp3", asset.originalFileName());
        assertEquals("audio/mpeg", asset.contentType());
        assertFalse(Files.exists(staged.path()));
        assertEquals(tempDir.resolve("audio").resolve(CALL_ID + ".mp3").toAbsolutePath().normalize(),
                store.path(asset));
        try (InputStream input = store.open(asset)) {
            assertArrayEquals(expected, input.readAllBytes());
        }

        store.delete(asset);
        store.delete(asset);
        assertFalse(Files.exists(tempDir.resolve("audio").resolve(CALL_ID + ".mp3")));
    }

    @Test
    void rejectsInvalidOversizeAndOverlongAudioWithoutTempLeaks() throws Exception {
        LocalAudioStore store = new LocalAudioStore(config(MIN_AUDIO_LIMIT, MIN_STORAGE_LIMIT, 1));

        assertEquals("INVALID_MP3", assertThrows(CallRecordException.class,
                () -> store.stage(new ByteArrayInputStream("not mp3".getBytes()),
                        "call.mp3", "audio/mpeg")).code());
        assertEquals("INVALID_MP3", assertThrows(CallRecordException.class,
                () -> store.stage(new ByteArrayInputStream(fixtureBytes("short-ding.mp3")),
                        "call.wav", "audio/wav")).code());
        assertEquals("AUDIO_TOO_LARGE", assertThrows(CallRecordException.class,
                () -> store.stage(new FixedLengthInputStream(MIN_AUDIO_LIMIT + 1),
                        "call.mp3", "audio/mpeg")).code());
        assertEquals("AUDIO_TOO_LONG", assertThrows(CallRecordException.class,
                () -> store.stage(new ByteArrayInputStream(fixtureBytes("long-bell.mp3")),
                        "call.mp3", "audio/mpeg")).code());

        assertEquals(0, fileCount(tempDir.resolve("tmp")));
        assertEquals(0, fileCount(tempDir.resolve("audio")));
    }

    @Test
    void rejectsCapacityRaceAndDiscardsTheStagedFile() throws Exception {
        LocalAudioStore store = new LocalAudioStore(config(MIN_AUDIO_LIMIT, MIN_STORAGE_LIMIT, 7_200));
        LocalAudioStore.StagedAudio staged = store.stage(
                new ByteArrayInputStream(fixtureBytes("short-ding.mp3")),
                "call.mp3", "audio/mpeg");
        long existingSize = MIN_STORAGE_LIMIT - staged.sizeBytes() + 1;
        createSparseFile(tempDir.resolve("audio/existing.mp3"), existingSize);

        CallRecordException error = assertThrows(CallRecordException.class,
                () -> store.publish(CALL_ID, staged));
        assertEquals("LOCAL_STORAGE_FULL", error.code());
        assertFalse(Files.exists(staged.path()));
        assertFalse(Files.exists(tempDir.resolve("audio").resolve(CALL_ID + ".mp3")));
    }

    @Test
    void neverOverwritesAudioAlreadyOwnedByTheCallId() throws Exception {
        LocalAudioStore store = new LocalAudioStore(config(MIN_AUDIO_LIMIT, MIN_STORAGE_LIMIT, 7_200));
        LocalAudioStore.StagedAudio staged = store.stage(
                new ByteArrayInputStream(fixtureBytes("short-ding.mp3")),
                "call.mp3", "audio/mpeg");
        Path existing = tempDir.resolve("audio").resolve(CALL_ID + ".mp3");
        byte[] existingBytes = "existing-audio".getBytes();
        Files.write(existing, existingBytes, StandardOpenOption.CREATE_NEW);

        CallRecordException error = assertThrows(CallRecordException.class,
                () -> store.publish(CALL_ID, staged));
        assertEquals("CALL_AUDIO_ALREADY_EXISTS", error.code());
        assertArrayEquals(existingBytes, Files.readAllBytes(existing));
        assertFalse(Files.exists(staged.path()));
    }

    @Test
    void confinesStagedAndPublishedPathsToServerOwnedDirectories() throws Exception {
        LocalAudioStore store = new LocalAudioStore(config(MIN_AUDIO_LIMIT, MIN_STORAGE_LIMIT, 7_200));
        LocalAudioStore.StagedAudio staged = store.stage(
                new ByteArrayInputStream(fixtureBytes("short-ding.mp3")),
                "..\\..\\windows-path.mp3", "audio/mpeg");
        assertEquals("windows-path.mp3", staged.originalFileName());
        store.discard(staged);
        assertFalse(Files.exists(staged.path()));

        AudioAsset malicious = new AudioAsset(
                "../escape.mp3", "escape.mp3", 1, "a".repeat(64),
                "audio/mpeg", 1.0);
        CallRecordException error = assertThrows(CallRecordException.class,
                () -> store.path(malicious));
        assertEquals("CALL_AUDIO_PATH_INVALID", error.code());
    }

    private Config config(long maxAudioBytes, long storageMaxBytes, int maxDurationSeconds) {
        return new Config(Map.of(
                "CALL_RECORD_DATA_DIR", tempDir.toString(),
                "CALL_RECORD_MAX_AUDIO_BYTES", Long.toString(maxAudioBytes),
                "CALL_RECORD_STORAGE_MAX_BYTES", Long.toString(storageMaxBytes),
                "CALL_RECORD_MAX_DURATION_SECONDS", Integer.toString(maxDurationSeconds)));
    }

    private byte[] fixtureBytes(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/callrecord/" + name)) {
            if (input == null) throw new IOException("Missing fixture " + name);
            return input.readAllBytes();
        }
    }

    private static long fileCount(Path directory) throws IOException {
        if (!Files.exists(directory)) return 0;
        try (var files = Files.list(directory)) {
            return files.count();
        }
    }

    private static void createSparseFile(Path path, long size) throws IOException {
        Files.createDirectories(path.getParent());
        try (SeekableByteChannel channel = Files.newByteChannel(
                path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(size - 1);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        assertEquals(size, Files.size(path));
    }

    private static final class FixedLengthInputStream extends InputStream {
        private long remaining;

        private FixedLengthInputStream(long length) {
            this.remaining = length;
        }

        @Override
        public int read() {
            if (remaining == 0) return -1;
            remaining--;
            return 0;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (remaining == 0) return -1;
            int count = (int) Math.min(length, remaining);
            java.util.Arrays.fill(buffer, offset, offset + count, (byte) 0);
            remaining -= count;
            return count;
        }
    }
}
