package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.Config;
import com.crmforlogistics.messagecenter.ContactPointUtil;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class CallRecordService {
    private static final int MAX_CONTACT_ID_LENGTH = 512;
    private static final int MAX_REQUEST_ID_LENGTH = 255;
    private static final int MAX_ACTOR_LENGTH = 128;
    private static final int MAX_REVISION_LENGTH = 100_000;

    private final CallRecordRepository repository;
    private final LocalAudioStore audioStore;
    private final ContactGroups contactGroups;
    private final Clock clock;
    private final int queueCapacity;
    private final int maxRevisions;
    private final String model;
    private final Object queueLock = new Object();
    private final Set<String> createReservations = new HashSet<>();

    public CallRecordService(CallRecordRepository repository,
                             LocalAudioStore audioStore,
                             ContactGroups contactGroups,
                             Config config,
                             Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.audioStore = Objects.requireNonNull(audioStore, "audioStore");
        this.contactGroups = Objects.requireNonNull(contactGroups, "contactGroups");
        Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.queueCapacity = config.callRecordQueueCapacity();
        this.maxRevisions = config.callRecordMaxRevisions();
        this.model = config.funAsrModel();
    }

    public CallRecord create(CreateCallRecordCommand command, InputStream input)
            throws CallRecordException {
        try (PreparedCreate prepared = prepareCreate(command)) {
            if (prepared.existing() != null) return prepared.existing();
            LocalAudioStore.StagedAudio staged = audioStore.stage(
                    input, prepared.validated().originalFileName(),
                    prepared.validated().contentType());
            return create(prepared, staged);
        }
    }

    public PreparedCreate prepareCreate(CreateCallRecordCommand command)
            throws CallRecordException {
        ValidatedCreate validated = validateCreate(command);
        Binding binding = resolveBinding(validated.contactId(), validated.phonePointId());

        String reservationKey = binding.anchor() + '\u0000' + validated.clientRequestId();
        synchronized (queueLock) {
            Optional<CallRecord> existing = repository.findByIdempotency(
                    binding.anchor(), validated.clientRequestId());
            if (existing.isPresent()) {
                return new PreparedCreate(this, validated, binding, existing.get(), null);
            }
            if (createReservations.contains(reservationKey)
                    || repository.countPending() + createReservations.size() >= queueCapacity) {
                throw new CallRecordException(
                        "TRANSCRIPTION_QUEUE_FULL", 429,
                        "Transcription queue is full", true);
            }
            createReservations.add(reservationKey);
        }
        return new PreparedCreate(this, validated, binding, null, reservationKey);
    }

    public CallRecord create(PreparedCreate prepared, LocalAudioStore.StagedAudio staged)
            throws CallRecordException {
        if (prepared == null || !prepared.isActiveFor(this)) {
            audioStore.discard(staged);
            throw invalidPreparedCreate();
        }
        try {
            if (prepared.existing() != null) {
                audioStore.discard(staged);
                return prepared.existing();
            }
            if (staged == null) throw invalidPreparedCreate();
            ValidatedCreate validated = prepared.validated();
            Binding binding = prepared.binding();
            UUID id = UUID.randomUUID();
            AudioAsset asset = audioStore.publish(id, staged);
            Instant createdAt = clock.instant();
            CallRecord record = new CallRecord(
                    id, binding.anchor(), binding.phonePointId(), validated.direction(),
                    validated.occurredAt(), createdAt, validated.actor(),
                    validated.clientRequestId(), asset,
                    new Transcription("queued", model, 0, null, createdAt, null, null),
                    List.of(), null, 1);
            try {
                repository.saveNew(record);
                return record;
            } catch (CallRecordException saveFailure) {
                try {
                    audioStore.delete(asset);
                } catch (CallRecordException compensationFailure) {
                    compensationFailure.addSuppressed(saveFailure);
                    throw compensationFailure;
                }
                if (!"CALL_RECORD_IDEMPOTENCY_CONFLICT".equals(saveFailure.code())) {
                    throw saveFailure;
                }
                return repository.findByIdempotency(
                                binding.anchor(), validated.clientRequestId())
                        .orElseThrow(() -> new CallRecordException(
                                "CALL_RECORD_STORE_CORRUPT", 500,
                                "Idempotency winner is missing", false, saveFailure));
            }
        } finally {
            prepared.close();
        }
    }

    public CallRecord detail(UUID id, Set<String> allowedAnchorPointIds)
            throws CallRecordException {
        Objects.requireNonNull(id, "id");
        Set<String> allowed = normalizeAnchors(allowedAnchorPointIds);
        CallRecord record = repository.find(id).orElseThrow(CallRecordService::notFound);
        if (!allowed.contains(record.contactAnchorPointId())) {
            throw forbidden();
        }
        return record;
    }

    CallRecord authenticatedDetail(UUID id) throws CallRecordException {
        Objects.requireNonNull(id, "id");
        // The local runtime grants valid WeCom viewers tenant-wide CRM visibility.
        // Do not synthesize actor-specific ACLs until an authorization owner exists.
        return repository.find(id).orElseThrow(CallRecordService::notFound);
    }

    public List<CallRecord> list(Set<String> allowedAnchorPointIds)
            throws CallRecordException {
        Set<String> allowed = normalizeAnchors(allowedAnchorPointIds);
        return allowed.isEmpty() ? List.of() : repository.listByAnchors(allowed);
    }

    public CallRecord retry(UUID id, String actor, String clientRequestId)
            throws CallRecordException {
        Objects.requireNonNull(id, "id");
        requireActor(actor);
        requireText(clientRequestId, MAX_REQUEST_ID_LENGTH,
                "CALL_RECORD_INPUT_INVALID", "clientRequestId is invalid");
        CallRecord current = repository.find(id).orElseThrow(CallRecordService::notFound);
        CallRecord retried = CallRecordStateMachine.manualRetry(current, clock.instant());
        synchronized (queueLock) {
            if (repository.countPending() + createReservations.size() >= queueCapacity) {
                throw new CallRecordException(
                        "TRANSCRIPTION_QUEUE_FULL", 429,
                        "Transcription queue is full", true);
            }
            return repository.replace(retried, current.version());
        }
    }

    public CallRecord revise(UUID id, String text, String actor, long expectedVersion)
            throws CallRecordException {
        Objects.requireNonNull(id, "id");
        requireActor(actor);
        requireText(text, MAX_REVISION_LENGTH,
                "TRANSCRIPT_REVISION_INVALID", "Transcript revision is invalid");
        CallRecord current = repository.find(id).orElseThrow(CallRecordService::notFound);
        if (current.version() != expectedVersion) {
            throw transcriptVersionConflict(null);
        }
        CallRecord revised = CallRecordStateMachine.appendRevision(
                current, text, actor.trim(), clock.instant(), maxRevisions);
        try {
            return repository.replace(revised, expectedVersion);
        } catch (CallRecordException exception) {
            if ("CALL_RECORD_VERSION_CONFLICT".equals(exception.code())) {
                throw transcriptVersionConflict(exception);
            }
            throw exception;
        }
    }

    private ValidatedCreate validateCreate(CreateCallRecordCommand command)
            throws CallRecordException {
        if (command == null) throw invalidInput("Create command is required");
        requireText(command.contactId(), MAX_CONTACT_ID_LENGTH,
                "CALL_RECORD_INPUT_INVALID", "contactId is invalid");
        if (!("inbound".equals(command.direction())
                || "outbound".equals(command.direction()))) {
            throw invalidInput("direction is invalid");
        }
        Instant now = clock.instant();
        if (command.occurredAt() == null
                || command.occurredAt().isAfter(now.plusSeconds(300))) {
            throw invalidInput("occurredAt is invalid");
        }
        requireText(command.clientRequestId(), MAX_REQUEST_ID_LENGTH,
                "CALL_RECORD_INPUT_INVALID", "clientRequestId is invalid");
        requireText(command.originalFileName(), 255,
                "CALL_RECORD_INPUT_INVALID", "originalFileName is invalid");
        requireText(command.contentType(), 128,
                "CALL_RECORD_INPUT_INVALID", "contentType is invalid");
        requireActor(command.actor());
        String phone = command.phonePointId() == null || command.phonePointId().isBlank()
                ? "" : ContactPointUtil.normalizePointId(command.phonePointId());
        if (!phone.isEmpty() && !phone.startsWith("phone:")) {
            throw bindingInvalid();
        }
        return new ValidatedCreate(
                command.contactId().trim(), phone, command.direction(), command.occurredAt(),
                command.clientRequestId().trim(), command.originalFileName(),
                command.contentType(), command.actor().trim());
    }

    private Binding resolveBinding(String contactId, String selectedPhone)
            throws CallRecordException {
        final List<String> rawPoints;
        try {
            rawPoints = contactGroups.points(contactId);
        } catch (CallRecordException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CallRecordException(
                    "CONTACT_BINDING_UNAVAILABLE", 503,
                    "Unable to resolve contact binding", true, exception);
        }
        LinkedHashSet<String> points = new LinkedHashSet<>();
        if (rawPoints != null) {
            for (String rawPoint : rawPoints) {
                String normalized = ContactPointUtil.normalizePointId(rawPoint);
                if (!normalized.isBlank()) points.add(normalized);
            }
        }
        if (points.isEmpty()) throw bindingInvalid();
        Set<String> phones = points.stream()
                .filter(point -> point.startsWith("phone:"))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!phones.isEmpty()) {
            if (selectedPhone.isBlank() || !phones.contains(selectedPhone)) {
                throw bindingInvalid();
            }
            return new Binding(selectedPhone, selectedPhone);
        }
        if (!selectedPhone.isBlank()) throw bindingInvalid();
        return new Binding(points.iterator().next(), "");
    }

    private static Set<String> normalizeAnchors(Set<String> anchors) {
        Objects.requireNonNull(anchors, "allowedAnchorPointIds");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String anchor : anchors) {
            String point = ContactPointUtil.normalizePointId(anchor);
            if (!point.isBlank()) normalized.add(point);
        }
        return Set.copyOf(normalized);
    }

    private static void requireActor(String actor) throws CallRecordException {
        if (actor == null || actor.isBlank() || actor.length() > MAX_ACTOR_LENGTH
                || actor.indexOf('\u0000') >= 0) {
            throw new CallRecordException(
                    "AUTH_REQUIRED", 401, "Authenticated actor is required", false);
        }
    }

    private static void requireText(String value, int maximum, String code, String message)
            throws CallRecordException {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.indexOf('\u0000') >= 0) {
            throw new CallRecordException(code, 400, message, false);
        }
    }

    private static CallRecordException invalidInput(String message) {
        return new CallRecordException(
                "CALL_RECORD_INPUT_INVALID", 400, message, false);
    }

    private static CallRecordException bindingInvalid() {
        return new CallRecordException(
                "CONTACT_BINDING_INVALID", 400,
                "Phone identity does not belong to the current contact", false);
    }

    private static CallRecordException notFound() {
        return new CallRecordException(
                "CALL_RECORD_NOT_FOUND", 404, "Call record does not exist", false);
    }

    private static CallRecordException forbidden() {
        return new CallRecordException(
                "CALL_RECORD_FORBIDDEN", 403,
                "Call record is outside the current contact", false);
    }

    private static CallRecordException transcriptVersionConflict(Throwable cause) {
        return new CallRecordException(
                "TRANSCRIPT_VERSION_CONFLICT", 409,
                "Transcript version has changed", false, cause);
    }

    private static CallRecordException invalidPreparedCreate() {
        return new CallRecordException(
                "CALL_RECORD_PREPARE_INVALID", 500,
                "Prepared call record create is no longer active", false);
    }

    private void releaseReservation(String reservationKey) {
        if (reservationKey == null) return;
        synchronized (queueLock) {
            createReservations.remove(reservationKey);
        }
    }

    @FunctionalInterface
    public interface ContactGroups {
        List<String> points(String contactId) throws Exception;
    }

    public record CreateCallRecordCommand(
            String contactId,
            String phonePointId,
            String direction,
            Instant occurredAt,
            String clientRequestId,
            String originalFileName,
            String contentType,
            String actor) {}

    public static final class PreparedCreate implements AutoCloseable {
        private final CallRecordService owner;
        private final ValidatedCreate validated;
        private final Binding binding;
        private final CallRecord existing;
        private final String reservationKey;
        private boolean closed;

        private PreparedCreate(CallRecordService owner, ValidatedCreate validated,
                               Binding binding, CallRecord existing, String reservationKey) {
            this.owner = owner;
            this.validated = validated;
            this.binding = binding;
            this.existing = existing;
            this.reservationKey = reservationKey;
        }

        private ValidatedCreate validated() { return validated; }
        private Binding binding() { return binding; }
        public CallRecord existing() { return existing; }

        private synchronized boolean isActiveFor(CallRecordService service) {
            return !closed && owner == service;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            owner.releaseReservation(reservationKey);
        }
    }

    private record ValidatedCreate(
            String contactId,
            String phonePointId,
            String direction,
            Instant occurredAt,
            String clientRequestId,
            String originalFileName,
            String contentType,
            String actor) {}

    private record Binding(String anchor, String phonePointId) {}
}
