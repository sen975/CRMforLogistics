package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.Config;
import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.Mp3File;
import com.mpatric.mp3agic.UnsupportedTagException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class LocalAudioStore {
    private static final int COPY_BUFFER_BYTES = 64 * 1_024;

    private final Path root;
    private final Path temporaryDirectory;
    private final Path audioDirectory;
    private final long maxAudioBytes;
    private final long storageMaxBytes;
    private final int maxDurationSeconds;
    private final Object publishLock = new Object();

    public LocalAudioStore(Config config) {
        Objects.requireNonNull(config, "config");
        this.root = config.callRecordDataDir().toAbsolutePath().normalize();
        this.temporaryDirectory = root.resolve("tmp");
        this.audioDirectory = root.resolve("audio");
        this.maxAudioBytes = config.callRecordMaxAudioBytes();
        this.storageMaxBytes = config.callRecordStorageMaxBytes();
        this.maxDurationSeconds = config.callRecordMaxDurationSeconds();
    }

    public StagedAudio stage(InputStream source, String originalFileName, String contentType)
            throws CallRecordException {
        if (source == null) {
            throw invalidMp3("Audio stream is required", null);
        }
        String safeFileName = safeOriginalFileName(originalFileName);
        if (!"audio/mpeg".equalsIgnoreCase(normalizeContentType(contentType))) {
            throw invalidMp3("Only audio/mpeg MP3 files are accepted", null);
        }
        ensureDirectories();

        Path temporary = temporaryDirectory.resolve(UUID.randomUUID() + ".upload");
        MessageDigest digest = sha256();
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        byte[] prefix = new byte[10];
        int prefixLength = 0;
        long size = 0;
        boolean staged = false;
        try {
            try (FileChannel output = FileChannel.open(
                    temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                while (true) {
                    int read = source.read(buffer);
                    if (read < 0) break;
                    if (read == 0) continue;
                    if (size > maxAudioBytes - read) {
                        throw new CallRecordException(
                                "AUDIO_TOO_LARGE", 413,
                                "MP3 exceeds the configured size limit", false);
                    }
                    int copy = Math.min(read, prefix.length - prefixLength);
                    if (copy > 0) {
                        System.arraycopy(buffer, 0, prefix, prefixLength, copy);
                        prefixLength += copy;
                    }
                    digest.update(buffer, 0, read);
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                    while (bytes.hasRemaining()) output.write(bytes);
                    size += read;
                }
                output.force(true);
            }

            if (!hasMp3Signature(prefix, prefixLength)) {
                throw invalidMp3("MP3 signature is invalid", null);
            }
            final long durationMillis;
            try {
                durationMillis = new Mp3File(temporary).getLengthInMilliseconds();
            } catch (InvalidDataException | UnsupportedTagException | IOException exception) {
                throw invalidMp3("MP3 structure is invalid", exception);
            }
            if (durationMillis <= 0) {
                throw invalidMp3("MP3 duration is invalid", null);
            }
            if (durationMillis > maxDurationSeconds * 1_000L) {
                throw new CallRecordException(
                        "AUDIO_TOO_LONG", 400,
                        "MP3 exceeds the configured duration limit", false);
            }
            staged = true;
            return new StagedAudio(
                    temporary, safeFileName, size,
                    HexFormat.of().formatHex(digest.digest()), durationMillis / 1_000.0);
        } catch (CallRecordException exception) {
            throw exception;
        } catch (IOException exception) {
            throw ioFailure("Unable to stage call audio", exception);
        } finally {
            if (!staged) deleteQuietly(temporary);
        }
    }

    public AudioAsset publish(UUID callRecordId, StagedAudio staged) throws CallRecordException {
        Objects.requireNonNull(callRecordId, "callRecordId");
        requireStagedFile(staged);
        synchronized (publishLock) {
            Path target = audioDirectory.resolve(callRecordId + ".mp3");
            try {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new CallRecordException(
                            "CALL_AUDIO_ALREADY_EXISTS", 409,
                            "Call audio already exists", false);
                }
                long currentBytes = publishedBytes();
                if (currentBytes > storageMaxBytes - staged.sizeBytes()) {
                    throw new CallRecordException(
                            "LOCAL_STORAGE_FULL", 507,
                            "Local call audio capacity is exhausted", false);
                }
                Files.move(staged.path(), target, StandardCopyOption.ATOMIC_MOVE);
                try (FileChannel channel = FileChannel.open(target, StandardOpenOption.READ)) {
                    channel.force(true);
                }
                return new AudioAsset(
                        "audio/" + callRecordId + ".mp3",
                        staged.originalFileName(), staged.sizeBytes(), staged.sha256(),
                        "audio/mpeg", staged.durationSeconds());
            } catch (CallRecordException exception) {
                discard(staged);
                throw exception;
            } catch (AtomicMoveNotSupportedException exception) {
                discard(staged);
                throw new CallRecordException(
                        "CALL_AUDIO_ATOMIC_MOVE_UNSUPPORTED", 500,
                        "Call audio filesystem does not support atomic moves", false, exception);
            } catch (FileAlreadyExistsException exception) {
                discard(staged);
                throw new CallRecordException(
                        "CALL_AUDIO_ALREADY_EXISTS", 409,
                        "Call audio already exists", false, exception);
            } catch (IOException exception) {
                deleteQuietly(target);
                discard(staged);
                throw ioFailure("Unable to publish call audio", exception);
            }
        }
    }

    public InputStream open(AudioAsset asset) throws CallRecordException {
        Path path = resolveAssetPath(asset, true);
        try {
            return Files.newInputStream(path, StandardOpenOption.READ);
        } catch (IOException exception) {
            throw ioFailure("Unable to open call audio", exception);
        }
    }

    public Path path(AudioAsset asset) throws CallRecordException {
        return resolveAssetPath(asset, true);
    }

    public void discard(StagedAudio staged) {
        if (staged != null && isStagedPath(staged.path())) {
            deleteQuietly(staged.path());
        }
    }

    public void delete(AudioAsset asset) throws CallRecordException {
        Path path = resolveAssetPath(asset, false);
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw ioFailure("Unable to delete call audio", exception);
        }
    }

    private void ensureDirectories() throws CallRecordException {
        try {
            ensureDirectory(root);
            ensureDirectory(temporaryDirectory);
            ensureDirectory(audioDirectory);
        } catch (IOException exception) {
            throw ioFailure("Unable to initialize call audio directories", exception);
        }
    }

    private static void ensureDirectory(Path directory) throws IOException, CallRecordException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new CallRecordException(
                        "CALL_AUDIO_PATH_INVALID", 500,
                        "Call audio directory is not a real directory", false);
            }
            return;
        }
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) {
            throw new CallRecordException(
                    "CALL_AUDIO_PATH_INVALID", 500,
                    "Call audio directory must not be a symbolic link", false);
        }
    }

    private long publishedBytes() throws IOException, CallRecordException {
        long total = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(audioDirectory, "*.mp3")) {
            for (Path file : files) {
                if (Files.isSymbolicLink(file)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new CallRecordException(
                            "CALL_AUDIO_PATH_INVALID", 500,
                            "Published call audio contains an invalid entry", false);
                }
                long size = Files.size(file);
                if (Long.MAX_VALUE - total < size) {
                    throw new CallRecordException(
                            "LOCAL_STORAGE_FULL", 507,
                            "Local call audio capacity cannot be measured safely", false);
                }
                total += size;
            }
        }
        return total;
    }

    private void requireStagedFile(StagedAudio staged) throws CallRecordException {
        if (staged == null || !isStagedPath(staged.path()) || staged.sizeBytes() <= 0
                || staged.sizeBytes() > maxAudioBytes || staged.durationSeconds() <= 0
                || staged.durationSeconds() > maxDurationSeconds
                || staged.sha256() == null || !staged.sha256().matches("[0-9a-f]{64}")) {
            throw new CallRecordException(
                    "CALL_AUDIO_PATH_INVALID", 500,
                    "Staged call audio is invalid", false);
        }
        try {
            if (Files.isSymbolicLink(staged.path())
                    || !Files.isRegularFile(staged.path(), LinkOption.NOFOLLOW_LINKS)
                    || Files.size(staged.path()) != staged.sizeBytes()) {
                throw new CallRecordException(
                        "CALL_AUDIO_PATH_INVALID", 500,
                        "Staged call audio is missing or changed", false);
            }
        } catch (IOException exception) {
            throw ioFailure("Unable to inspect staged call audio", exception);
        }
    }

    private boolean isStagedPath(Path path) {
        if (path == null) return false;
        Path normalized = path.toAbsolutePath().normalize();
        Path name = normalized.getFileName();
        return Objects.equals(normalized.getParent(), temporaryDirectory)
                && name != null && name.toString().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.upload");
    }

    private Path resolveAssetPath(AudioAsset asset, boolean requireExisting)
            throws CallRecordException {
        if (asset == null || asset.relativePath() == null
                || asset.relativePath().contains("\\")) {
            throw invalidAssetPath();
        }
        String prefix = "audio/";
        String suffix = ".mp3";
        if (!asset.relativePath().startsWith(prefix) || !asset.relativePath().endsWith(suffix)) {
            throw invalidAssetPath();
        }
        String idText = asset.relativePath().substring(
                prefix.length(), asset.relativePath().length() - suffix.length());
        final UUID id;
        try {
            id = UUID.fromString(idText);
        } catch (IllegalArgumentException exception) {
            throw invalidAssetPath();
        }
        if (!id.toString().equals(idText)) throw invalidAssetPath();
        Path resolved = root.resolve(asset.relativePath()).normalize();
        if (!Objects.equals(resolved.getParent(), audioDirectory)) throw invalidAssetPath();
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(resolved)) {
            throw invalidAssetPath();
        }
        if (requireExisting
                && !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new CallRecordException(
                    "CALL_AUDIO_NOT_FOUND", 404, "Call audio does not exist", false);
        }
        return resolved;
    }

    private static String safeOriginalFileName(String value) throws CallRecordException {
        if (value == null) throw invalidMp3("MP3 filename is required", null);
        String normalized = value.replace('\\', '/').trim();
        int slash = normalized.lastIndexOf('/');
        String name = (slash >= 0 ? normalized.substring(slash + 1) : normalized).trim();
        if (name.isEmpty() || name.length() > 255
                || !name.toLowerCase(Locale.ROOT).endsWith(".mp3")) {
            throw invalidMp3("MP3 filename is invalid", null);
        }
        return name;
    }

    private static String normalizeContentType(String value) {
        if (value == null) return "";
        int separator = value.indexOf(';');
        return (separator < 0 ? value : value.substring(0, separator)).trim();
    }

    private static boolean hasMp3Signature(byte[] prefix, int length) {
        boolean id3 = length >= 3 && prefix[0] == 'I' && prefix[1] == 'D' && prefix[2] == '3';
        boolean frame = length >= 2 && (prefix[0] & 0xff) == 0xff && (prefix[1] & 0xe0) == 0xe0;
        return id3 || frame;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static CallRecordException invalidMp3(String message, Throwable cause) {
        return new CallRecordException("INVALID_MP3", 400, message, false, cause);
    }

    private static CallRecordException invalidAssetPath() {
        return new CallRecordException(
                "CALL_AUDIO_PATH_INVALID", 500, "Call audio path is invalid", false);
    }

    private static CallRecordException ioFailure(String message, IOException cause) {
        return new CallRecordException("CALL_AUDIO_IO", 500, message, true, cause);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The owning operation reports its primary structured failure.
        }
    }

    public record StagedAudio(
            Path path,
            String originalFileName,
            long sizeBytes,
            String sha256,
            double durationSeconds) {}
}
