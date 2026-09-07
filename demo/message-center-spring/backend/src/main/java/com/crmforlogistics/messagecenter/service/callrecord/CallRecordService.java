package com.crmforlogistics.messagecenter.service.callrecord;

import com.crmforlogistics.messagecenter.config.CallRecordConfig;
import com.crmforlogistics.messagecenter.config.FunAsrConfig;
import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import com.crmforlogistics.messagecenter.entity.CallTranscriptRevisionEntity;
import com.crmforlogistics.messagecenter.mapper.CallRecordMapper;
import com.crmforlogistics.messagecenter.mapper.CallTranscriptRevisionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicActivityRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class CallRecordService {
    private static final int MAX_CONTACT_ID_LENGTH = 512;
    private static final int MAX_REQUEST_ID_LENGTH = 255;
    private static final int MAX_ACTOR_LENGTH = 128;
    private static final int MAX_REVISION_LENGTH = 100_000;
    private static final int MAX_NOTE_CODE_POINTS = 4_000;

    private final CallRecordMapper mapper;
    private final CallTranscriptRevisionMapper revisionMapper;
    private final MinioAudioStore audioStore;
    private final ContactIdentityMapper contactIdentityMapper;
    private final int queueCapacity;
    private final int maxRevisions;
    private final String model;
    private final Clock clock;
    private final AiTopicActivityRecorder topicActivityRecorder;

    public CallRecordService(CallRecordMapper mapper,
                             CallTranscriptRevisionMapper revisionMapper,
                             MinioAudioStore audioStore,
                             ContactIdentityMapper contactIdentityMapper,
                             CallRecordConfig config,
                             FunAsrConfig funAsrConfig,
                             Clock clock) {
        this(mapper, revisionMapper, audioStore, contactIdentityMapper, config, funAsrConfig, clock, null);
    }

    @Autowired
    public CallRecordService(CallRecordMapper mapper,
                             CallTranscriptRevisionMapper revisionMapper,
                             MinioAudioStore audioStore,
                             ContactIdentityMapper contactIdentityMapper,
                             CallRecordConfig config,
                             FunAsrConfig funAsrConfig,
                             Clock clock,
                             AiTopicActivityRecorder topicActivityRecorder) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.revisionMapper = Objects.requireNonNull(revisionMapper, "revisionMapper");
        this.audioStore = Objects.requireNonNull(audioStore, "audioStore");
        this.contactIdentityMapper = Objects.requireNonNull(contactIdentityMapper, "contactIdentityMapper");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(funAsrConfig, "funAsrConfig");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.queueCapacity = config.queueCapacity();
        this.maxRevisions = config.maxRevisions();
        this.model = funAsrConfig.model();
        this.topicActivityRecorder = topicActivityRecorder;
    }

    public CallRecordEntity create(CreateCallRecordCommand command, InputStream input) {
        ValidatedCreate validated = validateCreate(command);
        Binding binding = resolveBinding(validated.contactId(), validated.phonePointId());

        return createPersisted(null, null, validated, binding, input);
    }

    public CallRecordEntity create(UUID ownerId, CreateCallRecordCommand command, InputStream input) {
        Objects.requireNonNull(ownerId, "ownerId");
        ValidatedCreate validated = validateCreate(command);
        UUID contactId;
        try {
            contactId = UUID.fromString(validated.contactId());
        } catch (IllegalArgumentException error) {
            throw bindingInvalid();
        }
        Binding binding = resolveBinding(ownerId, contactId, validated.phonePointId());
        return createPersisted(ownerId, contactId, validated, binding, input);
    }

    private CallRecordEntity createPersisted(UUID ownerId, UUID contactId, ValidatedCreate validated,
                                              Binding binding, InputStream input) {

        var existing = ownerId == null
                ? mapper.findByIdempotency(binding.anchor(), validated.clientRequestId())
                : mapper.findByOwnerAndIdempotency(ownerId, contactId, validated.clientRequestId());
        if (existing.isPresent()) return existing.get();

        if (mapper.countPending() >= queueCapacity) {
            throw new CallRecordException(
                    "TRANSCRIPTION_QUEUE_FULL", 429,
                    "Transcription queue is full", true);
        }

        MinioAudioStore.StagedAudio staged = audioStore.stage(
                input, validated.originalFileName(), validated.contentType());
        UUID id = UUID.randomUUID();
        MinioAudioStore.AudioAsset asset = audioStore.publish(id, staged);
        Instant createdAt = clock.instant();

        CallRecordEntity entity = new CallRecordEntity();
        entity.setId(id);
        entity.setOwnerUserId(ownerId);
        entity.setContactId(contactId);
        entity.setContactAnchorPointId(binding.anchor());
        entity.setPhonePointId(binding.phonePointId());
        entity.setDirection(validated.direction());
        entity.setOccurredAt(validated.occurredAt());
        entity.setCreatedAt(createdAt);
        entity.setCreatedBy(validated.actor());
        entity.setClientRequestId(validated.clientRequestId());
        entity.setNote(validated.note());
        entity.setAudioRelativePath(asset.relativePath());
        entity.setAudioOriginalFileName(asset.originalFileName());
        entity.setAudioSizeBytes(asset.sizeBytes());
        entity.setAudioSha256(asset.sha256());
        entity.setAudioContentType(asset.contentType());
        entity.setAudioDurationSeconds(asset.durationSeconds());
        entity.setAudioObjectKey(asset.objectKey());
        entity.setTranscriptionState("queued");
        entity.setTranscriptionModel(model);
        entity.setTranscriptionAttempts(0);
        entity.setTranscriptionNextAttemptAt(createdAt);
        entity.setCurrentRevisionId(null);
        entity.setVersion(1L);
        entity.setUpdatedAt(createdAt);

        try {
            mapper.insert(entity);
            return entity;
        } catch (Exception saveFailure) {
            try {
                audioStore.delete(asset);
            } catch (Exception compensationFailure) {
                compensationFailure.addSuppressed(saveFailure);
                throw new CallRecordException(
                        "CALL_RECORD_STORE_CORRUPT", 500,
                        "Failed to save call record and compensation also failed", false,
                        compensationFailure);
            }
            throw new CallRecordException(
                    "CALL_RECORD_STORE_CORRUPT", 500,
                    "Failed to save call record", false, saveFailure);
        }
    }

    public CallRecordEntity detail(UUID id) {
        Objects.requireNonNull(id, "id");
        return mapper.findById(id).orElseThrow(CallRecordService::notFound);
    }

    public CallRecordEntity detail(UUID ownerId, UUID id) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(id, "id");
        return mapper.findByIdAndOwner(id, ownerId).orElseThrow(CallRecordService::notFound);
    }

    public List<CallRecordEntity> listByAnchors(Set<String> anchors) {
        Set<String> allowed = normalizeAnchors(anchors);
        return allowed.isEmpty() ? List.of() : mapper.listByAnchors(allowed);
    }

    public CallRecordEntity retry(UUID id, String actor, String clientRequestId) {
        Objects.requireNonNull(id, "id");
        requireActor(actor);
        requireText(clientRequestId, MAX_REQUEST_ID_LENGTH,
                "CALL_RECORD_INPUT_INVALID", "clientRequestId is invalid");
        CallRecordEntity current = mapper.findById(id).orElseThrow(CallRecordService::notFound);
        if (mapper.countPending() >= queueCapacity) {
            throw new CallRecordException(
                    "TRANSCRIPTION_QUEUE_FULL", 429,
                    "Transcription queue is full", true);
        }
        long expectedVersion = current.getVersion();
        CallRecordEntity retried = CallRecordStateMachine.manualRetry(current, clock.instant());
        int rows = mapper.replace(retried, expectedVersion);
        if (rows == 0) throw versionConflict(null);
        return retried;
    }

    public CallRecordEntity retry(UUID ownerId, UUID id, String actor, String clientRequestId) {
        CallRecordEntity current = detail(ownerId, id);
        return retryCurrent(current, actor, clientRequestId);
    }

    private CallRecordEntity retryCurrent(CallRecordEntity current, String actor, String clientRequestId) {
        UUID id = current.getId();
        requireActor(actor);
        requireText(clientRequestId, MAX_REQUEST_ID_LENGTH,
                "CALL_RECORD_INPUT_INVALID", "clientRequestId is invalid");
        if (mapper.countPending() >= queueCapacity) {
            throw new CallRecordException(
                    "TRANSCRIPTION_QUEUE_FULL", 429,
                    "Transcription queue is full", true);
        }
        long expectedVersion = current.getVersion();
        CallRecordEntity retried = CallRecordStateMachine.manualRetry(current, clock.instant());
        int rows = mapper.replace(retried, expectedVersion);
        if (rows == 0) throw versionConflict(null);
        return retried;
    }

    @Transactional
    public CallRecordEntity revise(UUID ownerId, UUID id, String text, String actor,
                                   long expectedVersion) {
        detail(ownerId, id);
        return revise(id, text, actor, expectedVersion);
    }

    @Transactional
    public CallRecordEntity reviseNote(UUID ownerId, UUID id, String note, long expectedVersion) {
        detail(ownerId, id);
        return reviseNote(id, note, expectedVersion);
    }

    @Transactional
    public CallRecordEntity revise(UUID id, String text, String actor, long expectedVersion) {
        Objects.requireNonNull(id, "id");
        requireActor(actor);
        requireText(text, MAX_REVISION_LENGTH,
                "TRANSCRIPT_REVISION_INVALID", "Transcript revision is invalid");
        CallRecordEntity current = mapper.findById(id).orElseThrow(CallRecordService::notFound);
        if (current.getVersion() != expectedVersion) {
            throw transcriptVersionConflict(null);
        }
        int revisionCount = revisionMapper.listByCallRecordId(id).size();
        if (revisionCount >= maxRevisions) {
            throw new CallRecordException(
                    "TRANSCRIPT_REVISION_INVALID", 400,
                    "Maximum transcript revisions reached", false);
        }
        CallRecordEntity revised = CallRecordStateMachine.appendRevision(
                current, text, actor.trim(), clock.instant(), maxRevisions);
        CallTranscriptRevisionEntity revEntity = new CallTranscriptRevisionEntity();
        revEntity.setId(UUID.randomUUID());
        revEntity.setCallRecordId(id);
        revEntity.setText(text.trim());
        revEntity.setEditedAt(clock.instant());
        revEntity.setEditedBy(actor.trim());
        revEntity.setCreatedAt(clock.instant());
        revisionMapper.insert(revEntity);
        revised.setCurrentRevisionId(revEntity.getId());
        int rows = mapper.replace(revised, expectedVersion);
        if (rows == 0) throw transcriptVersionConflict(null);
        recordTopicActivity(revised);
        return revised;
    }

    @Transactional
    public CallRecordEntity reviseNote(UUID id, String note, long expectedVersion) {
        Objects.requireNonNull(id, "id");
        String normalizedNote = validateNote(note);
        CallRecordEntity current = mapper.findById(id).orElseThrow(CallRecordService::notFound);
        if (current.getVersion() != expectedVersion) {
            throw versionConflict(null);
        }
        int rows = mapper.updateNote(id, normalizedNote, expectedVersion);
        if (rows == 0) throw versionConflict(null);
        current.setNote(normalizedNote);
        current.setVersion(current.getVersion() + 1);
        recordTopicActivity(current);
        return current;
    }

    private void recordTopicActivity(CallRecordEntity record) {
        if (topicActivityRecorder == null) return;
        topicActivityRecorder.recordCall(record.getContactAnchorPointId(), record.getOccurredAt());
    }

    private ValidatedCreate validateCreate(CreateCallRecordCommand command) {
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
        String rawPhone = command.phonePointId();
        boolean phoneMissing = rawPhone == null || rawPhone.isBlank();
        String phone = phoneMissing ? "" : rawPhone.trim();
        if (phoneMissing) {
            throw new CallRecordException(
                    "PHONE_CONTACT_REQUIRED", 400,
                    "A contact phone point is required", false);
        }
        if (!phone.startsWith("phone:")) {
            throw bindingInvalid();
        }
        String note = validateNote(command.note());
        return new ValidatedCreate(
                command.contactId().trim(), phone, command.direction(), command.occurredAt(),
                command.clientRequestId().trim(), command.originalFileName(),
                command.contentType(), command.actor().trim(), note);
    }

    private Binding resolveBinding(String contactId, String selectedPhone) {
        try {
            UUID contactUuid = UUID.fromString(contactId);
            var identities = contactIdentityMapper.findByContactId(contactUuid);
            if (identities == null || identities.isEmpty()) throw bindingInvalid();
            LinkedHashSet<String> phones = new LinkedHashSet<>();
            for (var identity : identities) {
                if (identity.getNormalizedValue() != null
                        && identity.getChannelType() != null
                        && identity.getChannelType().equalsIgnoreCase("phone")) {
                    String phonePoint = "phone:" + identity.getNormalizedValue();
                    phones.add(phonePoint);
                }
            }
            if (phones.isEmpty()) {
                if (selectedPhone.isBlank()) {
                    for (var identity : identities) {
                        if (identity.getNormalizedValue() != null) {
                            return new Binding(identity.getNormalizedValue(), "");
                        }
                    }
                }
                throw bindingInvalid();
            }
            if (selectedPhone.isBlank() || !phones.contains(selectedPhone)) {
                throw bindingInvalid();
            }
            return new Binding(selectedPhone, selectedPhone);
        } catch (IllegalArgumentException e) {
            throw bindingInvalid();
        } catch (Exception e) {
            throw new CallRecordException(
                    "CONTACT_BINDING_UNAVAILABLE", 503,
                    "Unable to resolve contact binding", true, e);
        }
    }

    private Binding resolveBinding(UUID ownerId, UUID contactId, String selectedPhone) {
        try {
            var identities = contactIdentityMapper.findByContactIdAndOwner(contactId, ownerId);
            if (identities == null || identities.isEmpty()) throw bindingInvalid();
            for (var identity : identities) {
                if ("phone".equalsIgnoreCase(identity.getChannelType())
                        && identity.getNormalizedValue() != null) {
                    String phonePoint = "phone:" + identity.getNormalizedValue();
                    if (phonePoint.equals(selectedPhone)) return new Binding(phonePoint, phonePoint);
                }
            }
            throw bindingInvalid();
        } catch (CallRecordException error) {
            throw error;
        } catch (Exception error) {
            throw new CallRecordException("CONTACT_BINDING_UNAVAILABLE", 503,
                    "Unable to resolve contact binding", true, error);
        }
    }

    private static Set<String> normalizeAnchors(Set<String> anchors) {
        Objects.requireNonNull(anchors, "anchors");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String anchor : anchors) {
            if (anchor != null && !anchor.isBlank()) normalized.add(anchor.trim());
        }
        return Set.copyOf(normalized);
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank() || actor.length() > MAX_ACTOR_LENGTH
                || actor.indexOf(' ') >= 0) {
            throw new CallRecordException(
                    "AUTH_REQUIRED", 401, "Authenticated actor is required", false);
        }
    }

    private static void requireText(String value, int maximum, String code, String message) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.indexOf(' ') >= 0) {
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

    private static CallRecordException transcriptVersionConflict(Throwable cause) {
        return new CallRecordException(
                "TRANSCRIPT_VERSION_CONFLICT", 409,
                "Transcript version has changed", false, cause);
    }

    private static CallRecordException versionConflict(Throwable cause) {
        return new CallRecordException(
                "CALL_RECORD_VERSION_CONFLICT", 409,
                "Call record version has changed", false, cause);
    }

    private static String validateNote(String note) {
        if (note == null || note.isBlank()) return "";
        if (note.indexOf(' ') >= 0
                || note.codePointCount(0, note.length()) > MAX_NOTE_CODE_POINTS) {
            throw new CallRecordException(
                    "CALL_RECORD_NOTE_INVALID", 400,
                    "Call record note is invalid", false);
        }
        return note;
    }

    public record CreateCallRecordCommand(
            String contactId,
            String phonePointId,
            String direction,
            Instant occurredAt,
            String clientRequestId,
            String originalFileName,
            String contentType,
            String actor,
            String note) {
        public CreateCallRecordCommand {
            note = note == null ? "" : note;
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
            String actor,
            String note) {}

    private record Binding(String anchor, String phonePointId) {}
}
