package com.crmforlogistics.messagecenter.callrecord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import com.crmforlogistics.messagecenter.Config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

public final class FileCallRecordRepository implements CallRecordRepository {
    private static final long MAX_RECORD_JSON_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_INDEX_JSON_BYTES = 16L * 1024L * 1024L;
    private static final Pattern UUID_FILE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json");
    private static final Pattern INDEX_FILE = Pattern.compile("[0-9a-f]{64}\\.json");
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .registerTypeAdapter(Instant.class,
                    (JsonSerializer<Instant>) (value, type, context) ->
                            new com.google.gson.JsonPrimitive(value.toString()))
            .registerTypeAdapter(Instant.class,
                    (JsonDeserializer<Instant>) (json, type, context) ->
                            Instant.parse(json.getAsString()))
            .create();
    private static final Comparator<CallRecord> TIMELINE_ORDER =
            Comparator.comparing(CallRecord::occurredAt).reversed()
                    .thenComparing(CallRecord::createdAt, Comparator.reverseOrder())
                    .thenComparing(record -> record.id().toString());
    private static final Comparator<CallRecord> RUNNABLE_ORDER =
            Comparator.comparing((CallRecord record) -> Optional.ofNullable(
                            record.transcription().nextAttemptAt()).orElse(Instant.MIN))
                    .thenComparing(CallRecord::createdAt)
                    .thenComparing(record -> record.id().toString());

    private final Path root;
    private final Path recordsDirectory;
    private final Path indexesDirectory;
    private final Path temporaryDirectory;
    private final Path audioDirectory;
    private final Clock clock;
    private final int maxRecords;
    private final int maxAttempts;
    private final int maxSegments;
    private final int maxRevisions;
    private final int maxDurationSeconds;
    private final long maxAudioBytes;
    private final long maxResponseBytes;
    private final FileChannel lockChannel;
    private final FileLock runtimeLock;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<UUID, CallRecord> recordsById = new LinkedHashMap<>();
    private final Map<String, UUID> idempotencyIndex = new HashMap<>();
    private final Map<String, List<UUID>> anchorIndex = new HashMap<>();
    private boolean closed;

    private FileCallRecordRepository(Config config, Clock clock, Path root,
                                     FileChannel lockChannel, FileLock runtimeLock) {
        this.root = root;
        this.recordsDirectory = root.resolve("records");
        this.indexesDirectory = root.resolve("indexes");
        this.temporaryDirectory = root.resolve("tmp");
        this.audioDirectory = root.resolve("audio");
        this.clock = clock;
        this.maxRecords = config.callRecordMaxRecords();
        this.maxAttempts = config.callRecordMaxAttempts();
        this.maxSegments = config.callRecordMaxSegments();
        this.maxRevisions = config.callRecordMaxRevisions();
        this.maxDurationSeconds = config.callRecordMaxDurationSeconds();
        this.maxAudioBytes = config.callRecordMaxAudioBytes();
        this.maxResponseBytes = config.callRecordMaxResponseBytes();
        this.lockChannel = lockChannel;
        this.runtimeLock = runtimeLock;
    }

    public static FileCallRecordRepository open(Config config, Clock clock)
            throws CallRecordException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        Path root = config.callRecordDataDir().toAbsolutePath().normalize();
        FileChannel channel = null;
        FileLock acquired = null;
        try {
            ensureDirectory(root);
            ensureDirectory(root.resolve("records"));
            ensureDirectory(root.resolve("indexes"));
            ensureDirectory(root.resolve("tmp"));
            ensureDirectory(root.resolve("audio"));
            Path lockFile = root.resolve("runtime.lock");
            if (Files.isSymbolicLink(lockFile)) {
                throw corrupt("Runtime lock must not be a symbolic link", null);
            }
            channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                acquired = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                closeQuietly(channel, null);
                throw busy(exception);
            }
            if (acquired == null) {
                closeQuietly(channel, null);
                throw busy(null);
            }
            FileCallRecordRepository repository = new FileCallRecordRepository(
                    config, clock, root, channel, acquired);
            try {
                repository.loadAndReconcile();
                return repository;
            } catch (CallRecordException | RuntimeException exception) {
                repository.closeAfterOpenFailure(exception);
                throw exception;
            }
        } catch (CallRecordException exception) {
            if (acquired == null) closeQuietly(channel, exception);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            closeQuietly(channel, exception);
            throw ioFailure("Unable to open call record store", exception);
        }
    }

    @Override
    public Optional<CallRecord> find(UUID id) throws CallRecordException {
        Objects.requireNonNull(id, "id");
        lock.readLock().lock();
        try {
            ensureOpen();
            return Optional.ofNullable(recordsById.get(id));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<CallRecord> findByIdempotency(String anchorPointId, String clientRequestId)
            throws CallRecordException {
        requireLookupText(anchorPointId, "anchorPointId");
        requireLookupText(clientRequestId, "clientRequestId");
        lock.readLock().lock();
        try {
            ensureOpen();
            UUID id = idempotencyIndex.get(idempotencyKey(anchorPointId, clientRequestId));
            return Optional.ofNullable(id == null ? null : recordsById.get(id));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<CallRecord> listByAnchors(Set<String> anchorPointIds)
            throws CallRecordException {
        Objects.requireNonNull(anchorPointIds, "anchorPointIds");
        lock.readLock().lock();
        try {
            ensureOpen();
            LinkedHashSet<UUID> ids = new LinkedHashSet<>();
            for (String anchor : anchorPointIds) {
                requireLookupText(anchor, "anchorPointId");
                ids.addAll(anchorIndex.getOrDefault(anchor, List.of()));
            }
            return ids.stream().map(recordsById::get).filter(Objects::nonNull)
                    .sorted(TIMELINE_ORDER).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int countPending() throws CallRecordException {
        lock.readLock().lock();
        try {
            ensureOpen();
            return Math.toIntExact(recordsById.values().stream()
                    .filter(record -> Set.of("queued", "processing")
                            .contains(record.transcription().state()))
                    .count());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void saveNew(CallRecord record) throws CallRecordException {
        lock.writeLock().lock();
        try {
            ensureOpen();
            validateRecord(record, record == null ? null : record.id());
            if (recordsById.size() >= maxRecords) {
                throw new CallRecordException(
                        "CALL_RECORD_LIMIT_REACHED", 507,
                        "Call record limit has been reached", false);
            }
            if (recordsById.containsKey(record.id())) {
                throw new CallRecordException(
                        "CALL_RECORD_ID_CONFLICT", 409,
                        "Call record id already exists", false);
            }
            String uniqueKey = idempotencyKey(
                    record.contactAnchorPointId(), record.clientRequestId());
            if (idempotencyIndex.containsKey(uniqueKey)) {
                throw new CallRecordException(
                        "CALL_RECORD_IDEMPOTENCY_CONFLICT", 409,
                        "Call record request already exists", false);
            }

            List<UUID> previousIds = anchorIndex.getOrDefault(
                    record.contactAnchorPointId(), List.of());
            List<UUID> nextIds = new ArrayList<>(previousIds);
            nextIds.add(record.id());
            nextIds = sortedAnchorIds(nextIds, record);
            Path indexFile = indexPath(record.contactAnchorPointId());
            try {
                writeJson(indexFile,
                        new AnchorIndex(record.contactAnchorPointId(), nextIds), MAX_INDEX_JSON_BYTES);
                writeJson(recordPath(record.id()), record, MAX_RECORD_JSON_BYTES);
            } catch (CallRecordException primary) {
                try {
                    if (previousIds.isEmpty()) {
                        Files.deleteIfExists(indexFile);
                    } else {
                        writeJson(indexFile, new AnchorIndex(
                                record.contactAnchorPointId(), previousIds), MAX_INDEX_JSON_BYTES);
                    }
                } catch (IOException | CallRecordException rollback) {
                    primary.addSuppressed(rollback);
                }
                throw primary;
            }
            recordsById.put(record.id(), record);
            idempotencyIndex.put(uniqueKey, record.id());
            anchorIndex.put(record.contactAnchorPointId(), List.copyOf(nextIds));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public CallRecord replace(CallRecord replacement, long expectedVersion)
            throws CallRecordException {
        lock.writeLock().lock();
        try {
            ensureOpen();
            return replaceUnderWriteLock(replacement, expectedVersion);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<CallRecord> recoverProcessing(Instant now) throws CallRecordException {
        Objects.requireNonNull(now, "now");
        lock.writeLock().lock();
        try {
            ensureOpen();
            List<CallRecord> processing = recordsById.values().stream()
                    .filter(record -> "processing".equals(record.transcription().state()))
                    .sorted(Comparator.comparing(CallRecord::createdAt)
                            .thenComparing(record -> record.id().toString()))
                    .toList();
            List<CallRecord> recovered = new ArrayList<>(processing.size());
            for (CallRecord current : processing) {
                CallRecord next = CallRecordStateMachine.recover(current, now);
                recovered.add(replaceUnderWriteLock(next, current.version()));
            }
            return List.copyOf(recovered);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<CallRecord> listRunnable(Instant now, int limit) throws CallRecordException {
        Objects.requireNonNull(now, "now");
        if (limit < 1 || limit > maxRecords) {
            throw new IllegalArgumentException("limit is outside its bounds");
        }
        lock.readLock().lock();
        try {
            ensureOpen();
            return recordsById.values().stream()
                    .filter(record -> "queued".equals(record.transcription().state()))
                    .filter(record -> record.transcription().nextAttemptAt() == null
                            || !record.transcription().nextAttemptAt().isAfter(now))
                    .sorted(RUNNABLE_ORDER)
                    .limit(limit)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() throws CallRecordException {
        lock.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            IOException failure = null;
            try {
                runtimeLock.release();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                lockChannel.close();
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
            if (failure != null) {
                throw ioFailure("Unable to close call record store", failure);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void loadAndReconcile() throws CallRecordException {
        lock.writeLock().lock();
        try {
            Instant cutoff = clock.instant().minusSeconds(3_600);
            cleanTemporaryDirectory(temporaryDirectory, cutoff);
            cleanAtomicTemps(recordsDirectory, cutoff);
            cleanAtomicTemps(indexesDirectory, cutoff);

            Map<UUID, CallRecord> loadedRecords = new LinkedHashMap<>();
            Map<String, UUID> loadedIdempotency = new HashMap<>();
            Map<String, List<UUID>> loadedAnchors = new HashMap<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(recordsDirectory)) {
                for (Path entry : entries) {
                    if (isAtomicTemp(entry)) continue;
                    requireRegularFile(entry, "Record store contains an invalid entry");
                    String name = entry.getFileName().toString();
                    if (!UUID_FILE.matcher(name).matches()) {
                        throw corrupt("Record filename is invalid", null);
                    }
                    if (loadedRecords.size() >= maxRecords) {
                        throw corrupt("Record store exceeds its configured limit", null);
                    }
                    UUID fileId = UUID.fromString(name.substring(0, name.length() - 5));
                    CallRecord record = readRecord(entry, fileId);
                    if (loadedRecords.putIfAbsent(record.id(), record) != null) {
                        throw corrupt("Record id is duplicated", null);
                    }
                    String uniqueKey = idempotencyKey(
                            record.contactAnchorPointId(), record.clientRequestId());
                    if (loadedIdempotency.putIfAbsent(uniqueKey, record.id()) != null) {
                        throw corrupt("Record idempotency key is duplicated", null);
                    }
                    loadedAnchors.computeIfAbsent(
                            record.contactAnchorPointId(), ignored -> new ArrayList<>())
                            .add(record.id());
                }
            } catch (CallRecordException exception) {
                throw exception;
            } catch (IOException | RuntimeException exception) {
                throw corrupt("Unable to scan record store", exception);
            }

            recordsById.clear();
            recordsById.putAll(loadedRecords);
            idempotencyIndex.clear();
            idempotencyIndex.putAll(loadedIdempotency);
            anchorIndex.clear();
            for (Map.Entry<String, List<UUID>> entry : loadedAnchors.entrySet()) {
                anchorIndex.put(entry.getKey(), List.copyOf(sortedAnchorIds(entry.getValue(), null)));
            }
            reconcileIndexes();
            reconcileAudio(cutoff);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private CallRecord replaceUnderWriteLock(CallRecord replacement, long expectedVersion)
            throws CallRecordException {
        validateRecord(replacement, replacement == null ? null : replacement.id());
        CallRecord current = recordsById.get(replacement.id());
        if (current == null) {
            throw new CallRecordException(
                    "CALL_RECORD_NOT_FOUND", 404, "Call record does not exist", false);
        }
        if (current.version() != expectedVersion
                || replacement.version() != expectedVersion + 1) {
            throw new CallRecordException(
                    "CALL_RECORD_VERSION_CONFLICT", 409,
                    "Call record version has changed", false);
        }
        if (!sameIdentity(current, replacement)) {
            throw corrupt("Replacement changes immutable record identity", null);
        }
        writeJson(recordPath(replacement.id()), replacement, MAX_RECORD_JSON_BYTES);
        recordsById.put(replacement.id(), replacement);
        return replacement;
    }

    private void reconcileIndexes() throws CallRecordException {
        Set<Path> expected = new HashSet<>();
        for (Map.Entry<String, List<UUID>> entry : anchorIndex.entrySet()) {
            Path path = indexPath(entry.getKey());
            expected.add(path);
            AnchorIndex actual = readIndexOrNull(path);
            AnchorIndex desired = new AnchorIndex(entry.getKey(), entry.getValue());
            if (!desired.equals(actual)) {
                writeJson(path, desired, MAX_INDEX_JSON_BYTES);
            }
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(indexesDirectory)) {
            for (Path entry : entries) {
                if (isAtomicTemp(entry)) continue;
                requireRegularFile(entry, "Index store contains an invalid entry");
                String name = entry.getFileName().toString();
                if (!INDEX_FILE.matcher(name).matches() || !expected.contains(entry)) {
                    Files.delete(entry);
                }
            }
        } catch (CallRecordException exception) {
            throw exception;
        } catch (IOException exception) {
            throw ioFailure("Unable to reconcile call record indexes", exception);
        }
    }

    private AnchorIndex readIndexOrNull(Path path) throws CallRecordException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        requireRegularFile(path, "Index store contains an invalid entry");
        try {
            long size = Files.size(path);
            if (size <= 0 || size > MAX_INDEX_JSON_BYTES) return null;
            AnchorIndex index = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8),
                    AnchorIndex.class);
            if (index == null || index.anchor() == null || index.recordIds() == null
                    || !Objects.equals(path, indexPath(index.anchor()))
                    || index.recordIds().stream().anyMatch(Objects::isNull)) {
                return null;
            }
            return new AnchorIndex(index.anchor(), List.copyOf(index.recordIds()));
        } catch (IOException exception) {
            throw ioFailure("Unable to read call record index", exception);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void reconcileAudio(Instant cutoff) throws CallRecordException {
        Set<Path> referenced = new HashSet<>();
        for (CallRecord record : recordsById.values()) {
            referenced.add(root.resolve(record.audio().relativePath()).normalize());
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(audioDirectory)) {
            for (Path entry : entries) {
                requireRegularFile(entry, "Audio store contains an invalid entry");
                if (!entry.getFileName().toString().endsWith(".mp3")) {
                    throw corrupt("Audio filename is invalid", null);
                }
                if (!referenced.contains(entry)
                        && Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS)
                        .toInstant().isBefore(cutoff)) {
                    Files.delete(entry);
                }
            }
        } catch (CallRecordException exception) {
            throw exception;
        } catch (IOException exception) {
            throw ioFailure("Unable to reconcile call record audio", exception);
        }
    }

    private CallRecord readRecord(Path path, UUID fileId) throws CallRecordException {
        try {
            long size = Files.size(path);
            if (size <= 0 || size > MAX_RECORD_JSON_BYTES) {
                throw corrupt("Record snapshot size is invalid", null);
            }
            CallRecord record = GSON.fromJson(
                    Files.readString(path, StandardCharsets.UTF_8), CallRecord.class);
            validateRecord(record, fileId);
            return record;
        } catch (CallRecordException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw corrupt("Record snapshot is invalid", exception);
        }
    }

    private void validateRecord(CallRecord record, UUID expectedId) throws CallRecordException {
        try {
            if (record == null || record.id() == null || !record.id().equals(expectedId)
                    || invalidText(record.contactAnchorPointId(), 512)
                    || (!record.phonePointId().isEmpty()
                    && (invalidText(record.phonePointId(), 512)
                    || !record.phonePointId().matches("phone:[0-9]+")))
                    || !("inbound".equals(record.direction())
                    || "outbound".equals(record.direction()))
                    || record.occurredAt() == null || record.createdAt() == null
                    || invalidCharacterText(record.createdBy(), 128)
                    || invalidCharacterText(record.clientRequestId(), 255)
                    || record.version() < 1) {
                throw corrupt("Record fields are invalid", null);
            }
            validateAudio(record);
            validateTranscription(record.transcription());
            validateRevisions(record);
        } catch (CallRecordException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw corrupt("Record fields are invalid", exception);
        }
    }

    private void validateAudio(CallRecord record) throws CallRecordException {
        AudioAsset audio = record.audio();
        String expectedPath = "audio/" + record.id() + ".mp3";
        if (audio == null || !expectedPath.equals(audio.relativePath())
                || invalidText(audio.originalFileName(), 255)
                || audio.sizeBytes() <= 0 || audio.sizeBytes() > maxAudioBytes
                || audio.sha256() == null || !audio.sha256().matches("[0-9a-f]{64}")
                || !"audio/mpeg".equals(audio.contentType())
                || !Double.isFinite(audio.durationSeconds())
                || audio.durationSeconds() <= 0 || audio.durationSeconds() > maxDurationSeconds) {
            throw corrupt("Record audio metadata is invalid", null);
        }
        Path audioPath = root.resolve(audio.relativePath()).normalize();
        if (!Objects.equals(audioPath.getParent(), audioDirectory)
                || Files.isSymbolicLink(audioPath)
                || !Files.isRegularFile(audioPath, LinkOption.NOFOLLOW_LINKS)) {
            throw corrupt("Record references missing audio", null);
        }
        try {
            if (Files.size(audioPath) != audio.sizeBytes()) {
                throw corrupt("Record audio size does not match", null);
            }
        } catch (IOException exception) {
            throw corrupt("Record audio cannot be inspected", exception);
        }
    }

    private void validateTranscription(Transcription transcription) throws CallRecordException {
        if (transcription == null || invalidText(transcription.model(), 128)
                || transcription.attempts() < 0 || transcription.attempts() > maxAttempts) {
            throw corrupt("Record transcription is invalid", null);
        }
        switch (transcription.state()) {
            case "queued" -> {
                if (transcription.lease() != null || transcription.result() != null) {
                    throw corrupt("Queued transcription is invalid", null);
                }
            }
            case "processing" -> {
                if (transcription.lease() == null || transcription.nextAttemptAt() != null
                        || transcription.result() != null || transcription.error() != null
                        || invalidText(transcription.lease().id(), 128)
                        || invalidText(transcription.lease().workerId(), 128)
                        || transcription.lease().expiresAt() == null) {
                    throw corrupt("Processing transcription is invalid", null);
                }
            }
            case "completed" -> {
                if (transcription.lease() != null || transcription.nextAttemptAt() != null
                        || transcription.error() != null || transcription.result() == null) {
                    throw corrupt("Completed transcription is invalid", null);
                }
                validateResult(transcription.result());
            }
            case "failed" -> {
                if (transcription.lease() != null || transcription.nextAttemptAt() != null
                        || transcription.result() != null || transcription.error() == null) {
                    throw corrupt("Failed transcription is invalid", null);
                }
            }
            default -> throw corrupt("Transcription state is invalid", null);
        }
        if (transcription.error() != null
                && (invalidText(transcription.error().code(), 128)
                || invalidText(transcription.error().message(), 2_048))) {
            throw corrupt("Transcription error is invalid", null);
        }
    }

    private void validateResult(TranscriptionResult result) throws CallRecordException {
        if (invalidText(result.model(), 128) || invalidText(result.originalText(), maxResponseBytes)
                || !Double.isFinite(result.durationSeconds()) || result.durationSeconds() <= 0
                || result.durationSeconds() > maxDurationSeconds || result.completedAt() == null
                || result.segments() == null || result.segments().isEmpty()
                || result.segments().size() > maxSegments) {
            throw corrupt("Transcription result is invalid", null);
        }
        double previousStart = -1;
        for (TranscriptSegment segment : result.segments()) {
            if (segment == null || invalidCharacterText(segment.text(), 100_000)
                    || !Double.isFinite(segment.startSeconds())
                    || !Double.isFinite(segment.endSeconds())
                    || segment.startSeconds() < 0 || segment.endSeconds() < segment.startSeconds()
                    || segment.endSeconds() > result.durationSeconds() + 1.0
                    || segment.startSeconds() < previousStart) {
                throw corrupt("Transcription segment is invalid", null);
            }
            previousStart = segment.startSeconds();
        }
    }

    private void validateRevisions(CallRecord record) throws CallRecordException {
        if (record.revisions() == null || record.revisions().size() > maxRevisions) {
            throw corrupt("Transcript revisions are invalid", null);
        }
        Set<UUID> ids = new HashSet<>();
        for (TranscriptRevision revision : record.revisions()) {
            if (revision == null || revision.id() == null || !ids.add(revision.id())
                    || invalidCharacterText(revision.text(), 100_000) || revision.editedAt() == null
                    || invalidCharacterText(revision.editedBy(), 128)) {
                throw corrupt("Transcript revision is invalid", null);
            }
        }
        if ((record.currentRevisionId() == null && !record.revisions().isEmpty())
                || (record.currentRevisionId() != null && !ids.contains(record.currentRevisionId()))) {
            throw corrupt("Current transcript revision is invalid", null);
        }
    }

    private List<UUID> sortedAnchorIds(List<UUID> ids, CallRecord additional) {
        Map<UUID, CallRecord> source = new HashMap<>(recordsById);
        if (additional != null) source.put(additional.id(), additional);
        return ids.stream().distinct()
                .sorted((left, right) -> TIMELINE_ORDER.compare(
                        source.get(left), source.get(right)))
                .toList();
    }

    private static boolean sameIdentity(CallRecord left, CallRecord right) {
        return left.id().equals(right.id())
                && left.contactAnchorPointId().equals(right.contactAnchorPointId())
                && left.phonePointId().equals(right.phonePointId())
                && left.direction().equals(right.direction())
                && left.occurredAt().equals(right.occurredAt())
                && left.createdAt().equals(right.createdAt())
                && left.createdBy().equals(right.createdBy())
                && left.clientRequestId().equals(right.clientRequestId())
                && left.audio().equals(right.audio());
    }

    private void writeJson(Path target, Object value, long maximumBytes)
            throws CallRecordException {
        final byte[] bytes;
        try {
            bytes = GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            throw ioFailure("Unable to serialize call record data", exception);
        }
        if (bytes.length <= 0 || bytes.length > maximumBytes) {
            throw new CallRecordException(
                    "CALL_RECORD_STORE_IO", 500,
                    "Call record data exceeds its storage bound", false);
        }
        Path temporary = target.resolveSibling(
                "." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw ioFailure("Call record filesystem does not support atomic moves", exception);
        } catch (IOException exception) {
            throw ioFailure("Unable to write call record data", exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Startup reconciliation removes stale same-directory temp files.
            }
        }
    }

    private void cleanTemporaryDirectory(Path directory, Instant cutoff)
            throws CallRecordException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                requireRegularFile(entry, "Temporary store contains an invalid entry");
                if (Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS)
                        .toInstant().isBefore(cutoff)) {
                    Files.delete(entry);
                }
            }
        } catch (CallRecordException exception) {
            throw exception;
        } catch (IOException exception) {
            throw ioFailure("Unable to reconcile temporary call record files", exception);
        }
    }

    private void cleanAtomicTemps(Path directory, Instant cutoff) throws CallRecordException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (!isAtomicTemp(entry)) continue;
                requireRegularFile(entry, "Atomic temporary file is invalid");
                if (Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS)
                        .toInstant().isBefore(cutoff)) {
                    Files.delete(entry);
                }
            }
        } catch (CallRecordException exception) {
            throw exception;
        } catch (IOException exception) {
            throw ioFailure("Unable to reconcile atomic temporary files", exception);
        }
    }

    private static boolean isAtomicTemp(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(".") && name.endsWith(".tmp");
    }

    private static void requireRegularFile(Path path, String message)
            throws CallRecordException {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw corrupt(message, null);
        }
    }

    private static void ensureDirectory(Path directory) throws IOException, CallRecordException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw corrupt("Call record directory is invalid", null);
            }
            return;
        }
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) {
            throw corrupt("Call record directory is invalid", null);
        }
    }

    private Path recordPath(UUID id) {
        return recordsDirectory.resolve(id + ".json");
    }

    private Path indexPath(String anchor) {
        return indexesDirectory.resolve(sha256(anchor) + ".json");
    }

    private void ensureOpen() throws CallRecordException {
        if (closed) {
            throw new CallRecordException(
                    "CALL_RECORD_STORE_CLOSED", 503,
                    "Call record store is closed", true);
        }
    }

    private void closeAfterOpenFailure(Throwable primary) {
        closed = true;
        try {
            runtimeLock.release();
        } catch (IOException exception) {
            primary.addSuppressed(exception);
        }
        try {
            lockChannel.close();
        } catch (IOException exception) {
            primary.addSuppressed(exception);
        }
    }

    private static void closeQuietly(FileChannel channel, Throwable primary) {
        if (channel == null) return;
        try {
            channel.close();
        } catch (IOException exception) {
            if (primary != null) primary.addSuppressed(exception);
        }
    }

    private static String idempotencyKey(String anchor, String requestId) {
        return anchor + "\u0000" + requestId;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void requireLookupText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static boolean invalidText(String value, long maximumUtf8Bytes) {
        return value == null || value.isBlank()
                || value.indexOf('\u0000') >= 0
                || value.getBytes(StandardCharsets.UTF_8).length > maximumUtf8Bytes;
    }

    private static boolean invalidCharacterText(String value, int maximumCharacters) {
        return value == null || value.isBlank()
                || value.indexOf('\u0000') >= 0 || value.length() > maximumCharacters;
    }

    private static CallRecordException busy(Throwable cause) {
        return new CallRecordException(
                "CALL_RECORD_STORE_BUSY", 503,
                "Call record store is already in use", true, cause);
    }

    private static CallRecordException corrupt(String message, Throwable cause) {
        return new CallRecordException(
                "CALL_RECORD_STORE_CORRUPT", 500, message, false, cause);
    }

    private static CallRecordException ioFailure(String message, Throwable cause) {
        return new CallRecordException(
                "CALL_RECORD_STORE_IO", 500, message, true, cause);
    }

    private record AnchorIndex(String anchor, List<UUID> recordIds) {
        private AnchorIndex {
            recordIds = recordIds == null ? null : List.copyOf(recordIds);
        }
    }
}
