package com.crmforlogistics.messagecenter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class BoundedAuditFile implements AutoCloseable {
    private static final Object REGISTRY_MUTEX = new Object();
    private static final Map<Path, SharedHandle> REGISTRY = new HashMap<>();

    enum Durability {
        BEST_EFFORT,
        REQUIRED
    }

    enum StartupState {
        STARTUP_CHECKS_PENDING,
        AUTHORIZATION_RECOVERY_PREPARED,
        RETENTION_CLEANUP_ENABLED
    }

    private final Path registryKey;
    private final SharedHandle handle;
    private boolean closed;

    private BoundedAuditFile(Path registryKey, SharedHandle handle) {
        this.registryKey = registryKey;
        this.handle = handle;
    }

    static BoundedAuditFile acquire(String stream, AuditFileSettings settings,
                                    Clock clock, AuditDiskSpaceProbe diskSpaceProbe)
            throws AuditStorageException {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(diskSpaceProbe, "diskSpaceProbe");
        if (stream.isBlank()) {
            throw new IllegalArgumentException("stream must not be blank");
        }
        Path path = settings.file().toAbsolutePath().normalize();
        Fingerprint requested = new Fingerprint(stream, settings, clock, diskSpaceProbe);
        synchronized (REGISTRY_MUTEX) {
            SharedHandle existing = REGISTRY.get(path);
            if (existing != null) {
                if (!existing.fingerprint.matches(requested)) {
                    throw failure("AUDIT_SETTINGS_CONFLICT", stream, null);
                }
                existing.references++;
                return new BoundedAuditFile(path, existing);
            }
            SharedHandle created = SharedHandle.open(requested);
            REGISTRY.put(path, created);
            return new BoundedAuditFile(path, created);
        }
    }

    synchronized void append(byte[] jsonLine, Durability durability) throws AuditStorageException {
        Objects.requireNonNull(jsonLine, "jsonLine");
        Objects.requireNonNull(durability, "durability");
        ensureOpen();
        handle.append(jsonLine, durability);
    }

    Path path() {
        return registryKey;
    }

    synchronized void prepareForAuthorizationRecovery() {
        ensureOpenUnchecked();
        handle.prepareForAuthorizationRecovery();
    }

    synchronized void enableRetentionCleanup() {
        ensureOpenUnchecked();
        handle.enableRetentionCleanup();
    }

    synchronized StartupState startupState() {
        ensureOpenUnchecked();
        return handle.startupState();
    }

    @Override
    public void close() throws AuditStorageException {
        synchronized (REGISTRY_MUTEX) {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            handle.references--;
            if (handle.references > 0) {
                return;
            }
            REGISTRY.remove(registryKey, handle);
            handle.closeResources();
        }
    }

    private synchronized void ensureOpen() throws AuditStorageException {
        if (closed) {
            throw failure("AUDIT_WRITER_CLOSED", handle.fingerprint.stream, null);
        }
    }

    private synchronized void ensureOpenUnchecked() {
        if (closed) {
            throw new IllegalStateException("audit writer is closed");
        }
    }

    private static AuditStorageException failure(String code, String stream, Throwable cause) {
        return new AuditStorageException(code, stream, cause);
    }

    private record Fingerprint(String stream, AuditFileSettings settings, Clock clock,
                               AuditDiskSpaceProbe diskSpaceProbe) {
        private boolean matches(Fingerprint other) {
            return stream.equals(other.stream)
                    && settings.equals(other.settings)
                    && clock.equals(other.clock)
                    && diskSpaceProbe == other.diskSpaceProbe;
        }
    }

    private static final class SharedHandle {
        private final Fingerprint fingerprint;
        private final ReentrantLock operationLock = new ReentrantLock();
        private final FileChannel lockChannel;
        private final FileLock fileLock;
        private FileChannel dataChannel;
        private int references = 1;
        private StartupState startupState = StartupState.STARTUP_CHECKS_PENDING;

        private SharedHandle(Fingerprint fingerprint, FileChannel dataChannel,
                             FileChannel lockChannel, FileLock fileLock) {
            this.fingerprint = fingerprint;
            this.dataChannel = dataChannel;
            this.lockChannel = lockChannel;
            this.fileLock = fileLock;
        }

        private static SharedHandle open(Fingerprint fingerprint) throws AuditStorageException {
            Path path = fingerprint.settings.file();
            Path parent = path.getParent();
            FileChannel lockChannel = null;
            FileLock fileLock = null;
            FileChannel dataChannel = null;
            try {
                Files.createDirectories(parent);
                Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
                lockChannel = FileChannel.open(lockPath,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                try {
                    fileLock = lockChannel.tryLock();
                } catch (OverlappingFileLockException exception) {
                    throw failure("AUDIT_WRITER_LOCKED", fingerprint.stream, exception);
                } catch (IOException exception) {
                    throw failure("AUDIT_ROTATION_FAILED", fingerprint.stream, exception);
                }
                if (fileLock == null) {
                    throw failure("AUDIT_WRITER_LOCKED", fingerprint.stream, null);
                }
                dataChannel = openDataChannel(path);
                return new SharedHandle(fingerprint, dataChannel, lockChannel, fileLock);
            } catch (AuditStorageException exception) {
                closeAfterOpenFailure(dataChannel, fileLock, lockChannel, exception);
                throw exception;
            } catch (IOException | RuntimeException exception) {
                AuditStorageException failure = failure(
                        "AUDIT_ROTATION_FAILED", fingerprint.stream, exception);
                closeAfterOpenFailure(dataChannel, fileLock, lockChannel, failure);
                throw failure;
            }
        }

        private void append(byte[] jsonLine, Durability durability)
                throws AuditStorageException {
            long maxBytes = fingerprint.settings.fileMaxBytes();
            if (jsonLine.length > maxBytes) {
                throw failure("AUDIT_EVENT_TOO_LARGE", fingerprint.stream, null);
            }
            ParsedLine incoming = validateLine(jsonLine, fingerprint.stream);
            operationLock.lock();
            try {
                Path path = fingerprint.settings.file();
                long currentSize = dataChannel.size();
                LocalDate currentDate = validateCurrent(path, currentSize,
                        fingerprint.settings.fileMaxBytes(), fingerprint.stream);
                LocalDate utcToday = LocalDate.ofInstant(fingerprint.clock.instant(), ZoneOffset.UTC);
                if (currentSize > 0
                        && (currentSize + jsonLine.length > maxBytes
                        || !currentDate.equals(utcToday))) {
                    rotate(currentDate);
                    currentSize = 0L;
                }
                writeWholeLine(jsonLine, currentSize, durability);
            } catch (AuditStorageException exception) {
                throw exception;
            } catch (IOException | RuntimeException exception) {
                throw failure("AUDIT_ROTATION_FAILED", fingerprint.stream, exception);
            } finally {
                operationLock.unlock();
            }
        }

        private void writeWholeLine(byte[] jsonLine, long initialSize, Durability durability)
                throws AuditStorageException {
            try {
                dataChannel.position(initialSize);
                ByteBuffer buffer = ByteBuffer.wrap(jsonLine);
                while (buffer.hasRemaining()) {
                    dataChannel.write(buffer);
                }
                dataChannel.force(durability == Durability.REQUIRED);
            } catch (IOException exception) {
                try {
                    dataChannel.truncate(initialSize);
                    dataChannel.force(true);
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw failure("AUDIT_ROTATION_FAILED", fingerprint.stream, exception);
            }
        }

        private void rotate(LocalDate currentDate) throws AuditStorageException {
            Path current = fingerprint.settings.file();
            RotationPaths paths = nextRotationPaths(current, currentDate, fingerprint.stream);
            try {
                dataChannel.force(true);
                dataChannel.close();
                dataChannel = null;
                atomicMove(current, paths.rotating);
                gzip(paths.rotating, paths.temporary);
                forceFile(paths.temporary);
                verifyGzip(paths.rotating, paths.temporary,
                        fingerprint.settings.fileMaxBytes());
                atomicMove(paths.temporary, paths.archive);
                Files.delete(paths.rotating);
                dataChannel = openDataChannel(current);
            } catch (IOException | RuntimeException exception) {
                if (dataChannel == null && Files.exists(current)) {
                    try {
                        dataChannel = openDataChannel(current);
                    } catch (IOException reopenFailure) {
                        exception.addSuppressed(reopenFailure);
                    }
                }
                throw failure("AUDIT_ROTATION_FAILED", fingerprint.stream, exception);
            }
        }

        private void prepareForAuthorizationRecovery() {
            operationLock.lock();
            try {
                if (startupState != StartupState.STARTUP_CHECKS_PENDING) {
                    throw new IllegalStateException(
                            "authorization recovery can only be prepared once before cleanup");
                }
                startupState = StartupState.AUTHORIZATION_RECOVERY_PREPARED;
            } finally {
                operationLock.unlock();
            }
        }

        private void enableRetentionCleanup() {
            operationLock.lock();
            try {
                if (startupState == StartupState.RETENTION_CLEANUP_ENABLED) {
                    throw new IllegalStateException("retention cleanup is already enabled");
                }
                startupState = StartupState.RETENTION_CLEANUP_ENABLED;
            } finally {
                operationLock.unlock();
            }
        }

        private StartupState startupState() {
            operationLock.lock();
            try {
                return startupState;
            } finally {
                operationLock.unlock();
            }
        }

        private void closeResources() throws AuditStorageException {
            operationLock.lock();
            try {
                IOException failure = null;
                failure = closeResource(dataChannel, failure);
                dataChannel = null;
                failure = closeResource(fileLock, failure);
                failure = closeResource(lockChannel, failure);
                if (failure != null) {
                    throw BoundedAuditFile.failure(
                            "AUDIT_WRITER_CLOSE_FAILED", fingerprint.stream, failure);
                }
            } finally {
                operationLock.unlock();
            }
        }
    }

    private static ParsedLine validateLine(byte[] bytes, String stream)
            throws AuditStorageException {
        if (bytes.length < 2 || bytes[bytes.length - 1] != '\n') {
            throw failure("AUDIT_CURRENT_CORRUPTED", stream, null);
        }
        for (int index = 0; index < bytes.length - 1; index++) {
            if (bytes[index] == '\n' || bytes[index] == '\r') {
                throw failure("AUDIT_CURRENT_CORRUPTED", stream, null);
            }
        }
        String json;
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, bytes.length - 1));
            json = decoded.toString();
        } catch (CharacterCodingException exception) {
            throw failure("AUDIT_CURRENT_CORRUPTED", stream, exception);
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw failure("AUDIT_CURRENT_CORRUPTED", stream, null);
            }
            JsonObject object = parsed.getAsJsonObject();
            JsonElement occurredAt = object.get("occurredAt");
            if (occurredAt == null || !occurredAt.isJsonPrimitive()
                    || !occurredAt.getAsJsonPrimitive().isString()) {
                throw failure("AUDIT_CURRENT_CORRUPTED", stream, null);
            }
            String timestamp = occurredAt.getAsString();
            if (!timestamp.endsWith("Z")) {
                throw failure("AUDIT_CURRENT_CORRUPTED", stream, null);
            }
            Instant instant = Instant.parse(timestamp);
            return new ParsedLine(LocalDate.ofInstant(instant, ZoneOffset.UTC));
        } catch (AuditStorageException exception) {
            throw exception;
        } catch (JsonParseException | DateTimeParseException | IllegalStateException exception) {
            throw failure("AUDIT_CURRENT_CORRUPTED", stream, exception);
        }
    }

    private static LocalDate validateCurrent(Path path, long expectedSize, long maxBytes,
                                             String stream)
            throws AuditStorageException {
        if (expectedSize == 0L) {
            return null;
        }
        if (expectedSize > maxBytes || expectedSize > Integer.MAX_VALUE) {
            throw failure("AUDIT_CURRENT_CORRUPTED", stream, null);
        }
        byte[] content;
        try {
            content = readBounded(path, maxBytes);
        } catch (IOException exception) {
            throw failure("AUDIT_CURRENT_CORRUPTED", stream, exception);
        }
        if (content.length != expectedSize || content[content.length - 1] != '\n') {
            throw failure("AUDIT_CURRENT_CORRUPTED", stream, null);
        }
        LocalDate firstDate = null;
        int start = 0;
        for (int index = 0; index < content.length; index++) {
            if (content[index] != '\n') {
                continue;
            }
            byte[] line = Arrays.copyOfRange(content, start, index + 1);
            ParsedLine parsed = validateLine(line, stream);
            if (firstDate == null) {
                firstDate = parsed.date;
            }
            start = index + 1;
        }
        return firstDate;
    }

    private static RotationPaths nextRotationPaths(Path current, LocalDate date, String stream)
            throws AuditStorageException {
        String name = current.getFileName().toString();
        String stem = name.substring(0, name.length() - ".jsonl".length());
        for (int sequence = 1; sequence > 0; sequence++) {
            String base = stem + "." + date + "." + String.format("%03d", sequence)
                    + ".jsonl";
            Path rotating = current.resolveSibling(base + ".rotating");
            Path temporary = current.resolveSibling(base + ".gz.tmp");
            Path archive = current.resolveSibling(base + ".gz");
            if (!Files.exists(rotating) && !Files.exists(temporary) && !Files.exists(archive)) {
                return new RotationPaths(rotating, temporary, archive);
            }
        }
        throw failure("AUDIT_ROTATION_FAILED", stream, null);
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            throw new IOException("rotation target already exists: " + target);
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw exception;
        }
    }

    private static void gzip(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            throw new IOException("rotation target already exists: " + target);
        }
        try (InputStream input = Files.newInputStream(source);
             OutputStream raw = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE);
             GZIPOutputStream gzip = new GZIPOutputStream(raw)) {
            input.transferTo(gzip);
            gzip.finish();
        }
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void verifyGzip(Path source, Path archive, long maxBytes) throws IOException {
        byte[] expected = readBounded(source, maxBytes);
        ByteArrayOutputStream restored = new ByteArrayOutputStream(expected.length);
        try (GZIPInputStream input = new GZIPInputStream(Files.newInputStream(archive))) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if ((long) restored.size() + read > maxBytes) {
                    throw new IOException("gzip verification exceeded configured limit");
                }
                restored.write(buffer, 0, read);
            }
        }
        if (!Arrays.equals(expected, restored.toByteArray())) {
            throw new IOException("gzip verification mismatch");
        }
    }

    private static FileChannel openDataChannel(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        channel.position(channel.size());
        return channel;
    }

    private static byte[] readBounded(Path path, long maxBytes) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                     (int) Math.min(maxBytes, 8_192L))) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if ((long) output.size() + read > maxBytes) {
                    throw new IOException("audit file exceeds configured limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void closeAfterOpenFailure(FileChannel dataChannel, FileLock fileLock,
                                              FileChannel lockChannel, Throwable failure) {
        IOException closeFailure = null;
        closeFailure = closeResource(dataChannel, closeFailure);
        closeFailure = closeResource(fileLock, closeFailure);
        closeFailure = closeResource(lockChannel, closeFailure);
        if (closeFailure != null) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static IOException closeResource(AutoCloseable resource, IOException prior) {
        if (resource == null) {
            return prior;
        }
        try {
            resource.close();
        } catch (Exception exception) {
            IOException failure = exception instanceof IOException io
                    ? io : new IOException(exception);
            if (prior == null) {
                return failure;
            }
            prior.addSuppressed(failure);
        }
        return prior;
    }

    private record ParsedLine(LocalDate date) {
    }

    private record RotationPaths(Path rotating, Path temporary, Path archive) {
    }
}
