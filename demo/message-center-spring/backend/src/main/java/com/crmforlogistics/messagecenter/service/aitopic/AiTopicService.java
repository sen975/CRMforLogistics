package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.entity.AiTopicGenerationJobEntity;
import com.crmforlogistics.messagecenter.entity.AiTopicItemEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicGenerationJobMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicItemMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicVersionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.GenerationProjection;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.GenerationStatus;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.InputBatch;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicContext;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicProjection;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicSourceItem;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicTimelineResponse;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.SourceType;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicOperationKind;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicOperationProjection;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicOperationStatus;
import com.crmforlogistics.messagecenter.entity.AiTopicOperationJobEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicOperationJobMapper;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class AiTopicService {
    private final ContactService contactService;
    private final AiTopicInputService inputService;
    private final AiTopicMapper topicMapper;
    private final AiTopicItemMapper itemMapper;
    private final AiTopicGenerationJobMapper jobMapper;
    private final AiTopicVersionMapper versionMapper;
    private final AiTopicConfigHolder configHolder;
    private final ContactIdentityMapper contactIdentityMapper;
    private final AiTopicOperationJobMapper operationJobMapper;
    private final EventHub eventHub;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public AiTopicService(ContactService contactService, AiTopicInputService inputService,
                          AiTopicMapper topicMapper, AiTopicItemMapper itemMapper,
                          AiTopicGenerationJobMapper jobMapper, AiTopicVersionMapper versionMapper,
                          AiTopicConfigHolder configHolder, ContactIdentityMapper contactIdentityMapper,
                          AiTopicOperationJobMapper operationJobMapper, EventHub eventHub) {
        this.contactService = contactService;
        this.inputService = inputService;
        this.topicMapper = topicMapper;
        this.itemMapper = itemMapper;
        this.jobMapper = jobMapper;
        this.versionMapper = versionMapper;
        this.configHolder = configHolder;
        this.contactIdentityMapper = contactIdentityMapper;
        this.operationJobMapper = operationJobMapper;
        this.eventHub = eventHub;
    }

    public AiTopicService(ContactService contactService, AiTopicInputService inputService,
                          AiTopicMapper topicMapper, AiTopicItemMapper itemMapper,
                          AiTopicGenerationJobMapper jobMapper, AiTopicVersionMapper versionMapper,
                          AiTopicConfigHolder configHolder, ContactIdentityMapper contactIdentityMapper) {
        this(contactService, inputService, topicMapper, itemMapper, jobMapper, versionMapper, configHolder,
                contactIdentityMapper, null, null);
    }

    public TopicTimelineResponse getTopics(UUID userId, UUID contactId) {
        contactService.getById(userId, contactId);
        InputBatch batch = inputService.collect(contactId, userId, java.util.Optional.empty());
        List<AiTopicEntity> topics = topicMapper.listReady(contactId);
        AiTopicGenerationJobEntity job = jobMapper.findByFingerprint(contactId, batch.fingerprint());
        GenerationStatus status = topics.isEmpty() ? GenerationStatus.NOT_STARTED : GenerationStatus.READY;
        boolean unsupported = batch.items().isEmpty() && contactHasWeCom(contactId);
        if (unsupported) return new TopicTimelineResponse(contactId,
                new GenerationProjection(GenerationStatus.NOT_STARTED, null, "WECOM_AI_UNSUPPORTED", Instant.now()),
                List.of(), true);
        if (!batch.items().isEmpty() && topics.isEmpty() && job == null) {
            jobMapper.insertIfAbsent(contactId, userId, "INITIAL", batch.fingerprint(), Instant.now());
            job = jobMapper.findByFingerprint(contactId, batch.fingerprint());
        } else if (!batch.items().isEmpty() && !topics.isEmpty()
                && topics.stream().noneMatch(topic -> batch.fingerprint().equals(topic.getInputFingerprint()))
                && job == null) {
            jobMapper.insertIfAbsent(contactId, userId, "INCREMENTAL", batch.fingerprint(), Instant.now());
            job = jobMapper.findByFingerprint(contactId, batch.fingerprint());
        }
        if (job != null && ("PENDING".equals(job.getStatus()) || "PROCESSING".equals(job.getStatus()) || "RETRY_WAIT".equals(job.getStatus()))) {
            status = GenerationStatus.GENERATING;
        } else if (job != null && "FAILED".equals(job.getStatus())) {
            status = GenerationStatus.FAILED;
        } else if (job != null && "COMPLETED".equals(job.getStatus()) && topics.isEmpty()) {
            status = GenerationStatus.FAILED;
        }
        return new TopicTimelineResponse(contactId,
                new GenerationProjection(status, job == null ? null : job.getId(), job == null ? null : job.getLastErrorCode(), job == null ? null : job.getUpdatedAt()),
                topics.stream().map(this::project).toList(), false);
    }

    @Transactional
    public TopicProjection updateTopic(UUID userId, UUID topicId, String title, String confirmedSummary, long expectedVersion) {
        AiTopicEntity current = requireTopic(userId, topicId);
        if (title == null || title.isBlank() || title.codePointCount(0, title.length()) > 200) throw new AiTopicException("TOPIC_INPUT_INVALID", false);
        if (confirmedSummary != null && (confirmedSummary.isBlank() || confirmedSummary.codePointCount(0, confirmedSummary.length()) > 4000)) throw new AiTopicException("TOPIC_INPUT_INVALID", false);
        if (topicMapper.updateEmployee(topicId, title.trim(), confirmedSummary, expectedVersion) == 0) throw new AiTopicException("TOPIC_VERSION_CONFLICT", false);
        versionMapper.insertVersion(topicId, expectedVersion + 1, "EMPLOYEE_EDITED", title.trim(), confirmedSummary == null ? current.getAiSummary() : confirmedSummary, "[]", userId);
        current.setTitle(title.trim()); current.setConfirmedSummary(confirmedSummary); current.setVersion(expectedVersion + 1);
        return project(current);
    }

    @Transactional
    public List<TopicProjection> mergeTopics(UUID userId, List<UUID> topicIds, Map<UUID, Long> expectedVersions) {
        if (topicIds == null || topicIds.size() < 2 || topicIds.size() > 20 || topicIds.stream().anyMatch(Objects::isNull) || topicIds.stream().distinct().count() != topicIds.size()
                || expectedVersions == null || topicIds.stream().anyMatch(id -> !expectedVersions.containsKey(id))) throw new AiTopicException("TOPIC_MERGE_INVALID", false);
        List<AiTopicEntity> topics = topicIds.stream().map(id -> requireTopic(userId, id)).sorted(Comparator.comparing(AiTopicEntity::getFirstOccurredAt).thenComparing(AiTopicEntity::getId)).toList();
        UUID contactId = topics.get(0).getContactId();
        if (topics.stream().anyMatch(t -> !contactId.equals(t.getContactId()))) throw new AiTopicException("TOPIC_MERGE_INVALID", false);
        AiTopicEntity target = topics.get(0);
        List<AiTopicItemEntity> allItems = new ArrayList<>();
        for (AiTopicEntity topic : topics) {
            Long expected = expectedVersions.get(topic.getId());
            if (!Objects.equals(expected, topic.getVersion())) throw new AiTopicException("TOPIC_VERSION_CONFLICT", false);
            allItems.addAll(itemMapper.listByTopic(topic.getId()));
            if (!topic.getId().equals(target.getId())) topicMapper.archive(topic.getId());
        }
        for (AiTopicItemEntity item : allItems) { item.setTopicId(target.getId()); itemMapper.updateById(item); }
        Instant last = allItems.stream().map(AiTopicItemEntity::getOccurredAt).max(Instant::compareTo).orElse(target.getLastOccurredAt());
        target.setLastOccurredAt(last); target.setVersion(target.getVersion() + 1);
        topicMapper.updateById(target);
        final String sourceTopicIds;
        try {
            sourceTopicIds = objectMapper.writeValueAsString(topicIds);
        } catch (Exception error) {
            throw new AiTopicException("TOPIC_MERGE_INVALID", false, error);
        }
        versionMapper.insertVersion(target.getId(), target.getVersion(), "MERGED", target.getTitle(), target.getAiSummary(), sourceTopicIds, userId);
        return List.of(project(target));
    }

    public GenerationProjection retryGeneration(UUID userId, UUID contactId) {
        contactService.getById(userId, contactId);
        InputBatch batch = inputService.collect(contactId, userId, java.util.Optional.empty());
        if (batch.items().isEmpty()) return new GenerationProjection(GenerationStatus.NOT_STARTED, null, "WECOM_AI_UNSUPPORTED", Instant.now());
        AiTopicGenerationJobEntity job = jobMapper.findByFingerprint(contactId, batch.fingerprint());
        if (job != null && "FAILED".equals(job.getStatus())) {
            jobMapper.requeueFailed(job.getId(), Instant.now());
        } else if (job == null) {
            jobMapper.insertIfAbsent(contactId, userId, "INCREMENTAL", batch.fingerprint(), Instant.now());
            job = jobMapper.findByFingerprint(contactId, batch.fingerprint());
        }
        return new GenerationProjection(GenerationStatus.GENERATING, job == null ? null : job.getId(), null, Instant.now());
    }

    @Transactional
    void generate(AiTopicGenerationJobEntity job, UUID userId, TopicAiGateway gateway) {
        if (userId == null || !userId.equals(job.getCreatedByUserId())) {
            throw new AiTopicException("AI_AUTH_CONTEXT_MISSING", false);
        }
        InputBatch batch = inputService.collect(job.getContactId(), userId, java.util.Optional.empty());
        List<TopicContext> contexts = topicMapper.listReady(job.getContactId()).stream().map(topic -> new TopicContext(topic.getId(), topic.getTitle(), topic.getConfirmedSummary() == null ? topic.getAiSummary() : topic.getConfirmedSummary(), topic.getFirstOccurredAt(), topic.getLastOccurredAt(), itemMapper.listByTopic(topic.getId()).stream().map(item -> item.getMessageId() != null ? item.getMessageId() : item.getCallRecordId()).filter(Objects::nonNull).toList())).toList();
        var output = gateway.generate(new AiTopicModels.GenerationInput(batch.items(), contexts, "INCREMENTAL".equals(job.getJobKind())));
        Map<UUID, AiTopicModels.SourceItem> byId = batch.items().stream().collect(java.util.stream.Collectors.toMap(AiTopicModels.SourceItem::id, x -> x));
        for (var assignment : output.assignments()) {
            List<AiTopicModels.SourceItem> sources = assignment.sourceIds().stream().map(byId::get).filter(Objects::nonNull).toList();
            if (sources.isEmpty()) continue;
            ResolvedTopic resolved = resolveTopic(assignment.topicKey(), job.getContactId(), sources, assignment.title(), assignment.summary(), assignment.relevance(), job.getInputFingerprint());
            AiTopicEntity topic = resolved.topic();
            boolean created = resolved.created();
            if (created) {
                int claimed = insertFirstSource(topic, sources.get(0));
                if (claimed == 0) {
                    topicMapper.deleteById(topic.getId());
                    continue;
                }
            }
            for (var source : sources.subList(created ? 1 : 0, sources.size())) {
                itemMapper.insertIfAbsent(topic.getId(), source.sourceType() == SourceType.MESSAGE ? source.id() : null,
                        source.sourceType() == SourceType.CALL_RECORD ? source.id() : null, source.occurredAt(), source.channelType());
            }
            topic.setLastOccurredAt(sources.stream().map(AiTopicModels.SourceItem::occurredAt).max(Instant::compareTo).orElse(topic.getLastOccurredAt())); topic.setInputFingerprint(job.getInputFingerprint()); topic.setVersion(topic.getVersion() == null ? 1 : topic.getVersion() + 1); topicMapper.updateById(topic); versionMapper.insertVersion(topic.getId(), topic.getVersion(), "AI_GENERATED", topic.getTitle(), topic.getAiSummary(), "[]", null);
        }
    }

    private int insertFirstSource(AiTopicEntity topic, AiTopicModels.SourceItem source) {
        return itemMapper.insertIfAbsent(topic.getId(), source.sourceType() == SourceType.MESSAGE ? source.id() : null,
                source.sourceType() == SourceType.CALL_RECORD ? source.id() : null, source.occurredAt(), source.channelType());
    }

    private ResolvedTopic resolveTopic(String key, UUID contactId, List<AiTopicModels.SourceItem> sources, String title, String summary, double relevance, String inputFingerprint) {
        if (relevance >= configHolder.get().matchThreshold()) {
            try { AiTopicEntity existing = topicMapper.selectById(UUID.fromString(key)); if (existing != null && contactId.equals(existing.getContactId()) && "READY".equals(existing.getStatus())) return new ResolvedTopic(existing, false); } catch (IllegalArgumentException ignored) {}
        }
        AiTopicEntity topic = new AiTopicEntity(); topic.setId(UUID.randomUUID()); topic.setContactId(contactId); topic.setTitle(title); topic.setAiSummary(summary); topic.setStatus("READY"); topic.setFirstOccurredAt(sources.stream().map(AiTopicModels.SourceItem::occurredAt).min(Instant::compareTo).orElse(Instant.now())); topic.setLastOccurredAt(sources.stream().map(AiTopicModels.SourceItem::occurredAt).max(Instant::compareTo).orElse(Instant.now())); topic.setVersion(1L); topic.setInputFingerprint(inputFingerprint); topicMapper.insert(topic); return new ResolvedTopic(topic, true);
    }

    private record ResolvedTopic(AiTopicEntity topic, boolean created) {}

    public TopicOperationProjection submitOperation(UUID userId, UUID contactId, TopicOperationKind kind,
                                                    String requestPayload, String expectedVersions,
                                                    String idempotencyKey) {
        contactService.getById(userId, contactId);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new AiTopicException("IDEMPOTENCY_KEY_INVALID", false);
        }
        AiTopicOperationJobEntity job = operationJobMapper.findOrCreate(contactId, userId, kind.name(),
                requestPayload == null ? "{}" : requestPayload, expectedVersions == null ? "{}" : expectedVersions,
                idempotencyKey, Instant.now());
        return operationProjection(job);
    }

    private TopicOperationProjection operationProjection(AiTopicOperationJobEntity job) {
        return new TopicOperationProjection(job.getId(), TopicOperationKind.valueOf(job.getOperationKind()),
                TopicOperationStatus.valueOf(job.getStatus()), job.getLastErrorCode(), job.getCreatedAt(), job.getCompletedAt());
    }

    @Transactional
    void applyOperation(AiTopicOperationJobEntity job) {
        try {
            Map<String, Object> payload = objectMapper.readValue(job.getRequestPayload(), Map.class);
            TopicOperationKind kind = TopicOperationKind.valueOf(job.getOperationKind());
            UUID actor = job.getCreatedByUserId();
            if (kind == TopicOperationKind.DISCARD || kind == TopicOperationKind.RESTORE || kind == TopicOperationKind.EDIT) {
                UUID topicId = UUID.fromString(String.valueOf(payload.get("topicId")));
                AiTopicEntity topic = requireTopic(actor, topicId);
                if (kind == TopicOperationKind.DISCARD) {
                    topicMapper.updateStatus(topicId, "DISCARDED");
                    versionMapper.insertVersion(topicId, topic.getVersion() + 1, "DISCARDED", topic.getTitle(), topic.getAiSummary(), "[]", actor);
                } else if (kind == TopicOperationKind.RESTORE) {
                    topicMapper.updateStatus(topicId, "READY");
                    versionMapper.insertVersion(topicId, topic.getVersion() + 1, "RESTORED", topic.getTitle(), topic.getAiSummary(), "[]", actor);
                } else {
                    updateTopic(actor, topicId, String.valueOf(payload.get("title")), (String) payload.get("confirmedSummary"), ((Number) payload.get("expectedVersion")).longValue());
                }
            } else {
                List<UUID> ids = ((List<?>) payload.get("topicIds")).stream().map(v -> UUID.fromString(String.valueOf(v))).toList();
                Map<UUID, Long> versions = new java.util.HashMap<>();
                Object expected = payload.get("expectedVersions");
                if (expected instanceof Map<?, ?> map) map.forEach((k, v) -> versions.put(UUID.fromString(String.valueOf(k)), ((Number) v).longValue()));
                mergeTopics(actor, ids, versions);
            }
        } catch (AiTopicException e) { throw e; }
        catch (Exception e) { throw new AiTopicException("TOPIC_OPERATION_INVALID", false, e); }
    }

    void publishSnapshotCompleted() { if (eventHub != null) eventHub.publish("topic-snapshot-completed", "{}"); }

    public IPage<TopicProjection> listDiscarded(UUID userId, String search, int page, int size) {
        Page<AiTopicEntity> query = new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size)));
        boolean admin = com.crmforlogistics.messagecenter.service.contact.ContactService.isCurrentUserAdmin();
        IPage<AiTopicEntity> result = topicMapper.listDiscardedForUser(query, userId, search, admin);
        Page<TopicProjection> output = new Page<>(query.getCurrent(), query.getSize());
        output.setTotal(result.getTotal());
        output.setRecords(result.getRecords().stream().map(this::project).toList());
        return output;
    }

    private AiTopicEntity requireTopic(UUID userId, UUID topicId) {
        AiTopicEntity topic = topicMapper.selectById(topicId);
        if (topic == null) throw new AiTopicException("TOPIC_NOT_FOUND", false);
        contactService.getById(userId, topic.getContactId());
        return topic;
    }

    private TopicProjection project(AiTopicEntity topic) {
        List<AiTopicItemEntity> items = itemMapper.listByTopic(topic.getId());
        List<String> channels = items.stream().map(AiTopicItemEntity::getChannelType).distinct().toList();
        List<TopicSourceItem> sources = items.stream().map(i -> new TopicSourceItem(i.getMessageId() != null ? i.getMessageId() : i.getCallRecordId(), i.getMessageId() != null ? SourceType.MESSAGE : SourceType.CALL_RECORD, i.getOccurredAt(), i.getChannelType())).toList();
        return new TopicProjection(topic.getId(), topic.getTitle(), topic.getConfirmedSummary() == null ? topic.getAiSummary() : topic.getConfirmedSummary(), topic.getConfirmedSummary() == null ? "AI" : "EMPLOYEE", topic.getFirstOccurredAt(), topic.getLastOccurredAt(), channels, sources.size(), sources, topic.getVersion(), topic.getContactId());
    }

    private boolean contactHasWeCom(UUID contactId) {
        return contactIdentityMapper.findByContactId(contactId).stream().anyMatch(i -> "wecom".equalsIgnoreCase(i.getChannelType()));
    }
}
