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
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.GroupTopicTimelineResponse;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.SourceType;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicOperationKind;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicOperationProjection;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicOperationStatus;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicInboxRequestProjection;
import com.crmforlogistics.messagecenter.entity.AiTopicOperationJobEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicOperationJobMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicInboxRequestMapper;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final AiTopicInboxRequestMapper inboxRequestMapper;
    private final EventHub eventHub;
    private final AiTopicGenerationAuditService generationAudit;
    private final com.crmforlogistics.messagecenter.mapper.AiTopicGenerationAttemptMapper attemptMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiTopicService(ContactService contactService, AiTopicInputService inputService,
                          AiTopicMapper topicMapper, AiTopicItemMapper itemMapper,
                          AiTopicGenerationJobMapper jobMapper, AiTopicVersionMapper versionMapper,
                          AiTopicConfigHolder configHolder, ContactIdentityMapper contactIdentityMapper,
                          AiTopicOperationJobMapper operationJobMapper, EventHub eventHub,
                          AiTopicGenerationAuditService generationAudit,
                          com.crmforlogistics.messagecenter.mapper.AiTopicGenerationAttemptMapper attemptMapper) {
        this(contactService, inputService, topicMapper, itemMapper, jobMapper, versionMapper, configHolder,
                contactIdentityMapper, operationJobMapper, null, eventHub, generationAudit, attemptMapper);
    }

    @Autowired
    public AiTopicService(ContactService contactService, AiTopicInputService inputService,
                          AiTopicMapper topicMapper, AiTopicItemMapper itemMapper,
                          AiTopicGenerationJobMapper jobMapper, AiTopicVersionMapper versionMapper,
                          AiTopicConfigHolder configHolder, ContactIdentityMapper contactIdentityMapper,
                          AiTopicOperationJobMapper operationJobMapper, AiTopicInboxRequestMapper inboxRequestMapper,
                          EventHub eventHub, AiTopicGenerationAuditService generationAudit,
                          com.crmforlogistics.messagecenter.mapper.AiTopicGenerationAttemptMapper attemptMapper) {
        this.contactService = contactService;
        this.inputService = inputService;
        this.topicMapper = topicMapper;
        this.itemMapper = itemMapper;
        this.jobMapper = jobMapper;
        this.versionMapper = versionMapper;
        this.configHolder = configHolder;
        this.contactIdentityMapper = contactIdentityMapper;
        this.operationJobMapper = operationJobMapper;
        this.inboxRequestMapper = inboxRequestMapper;
        this.eventHub = eventHub;
        this.generationAudit = generationAudit;
        this.attemptMapper = attemptMapper;
    }

    public AiTopicService(ContactService contactService, AiTopicInputService inputService,
                          AiTopicMapper topicMapper, AiTopicItemMapper itemMapper,
                          AiTopicGenerationJobMapper jobMapper, AiTopicVersionMapper versionMapper,
                          AiTopicConfigHolder configHolder, ContactIdentityMapper contactIdentityMapper) {
        this(contactService, inputService, topicMapper, itemMapper, jobMapper, versionMapper, configHolder,
                contactIdentityMapper, null, null, null, null, null);
    }

    public TopicTimelineResponse getTopics(UUID userId, UUID contactId) {
        contactService.getById(userId, contactId);
        InputBatch batch = inputService.collect(contactId, userId, java.util.Optional.empty());
        List<AiTopicEntity> topics = new ArrayList<>(topicMapper.listReady(contactId));
        List<AiTopicEntity> referencedGroups = topicMapper.listReadyReferencedGroupTopics(contactId);
        if (referencedGroups != null) topics.addAll(referencedGroups);
        topics.sort(Comparator.comparing(AiTopicEntity::getLastOccurredAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(AiTopicEntity::getId));
        AiTopicGenerationJobEntity job = jobMapper.findByFingerprint(contactId, batch.fingerprint());
        GenerationStatus status = topics.isEmpty() ? GenerationStatus.NOT_STARTED : GenerationStatus.READY;
        boolean unsupported = batch.items().isEmpty()
                && contactHasWeCom(contactId)
                && !contactHasSupportedChannel(contactId);
        if (unsupported) return new TopicTimelineResponse(contactId,
                new GenerationProjection(GenerationStatus.NOT_STARTED, null, "WECOM_AI_UNSUPPORTED", Instant.now()),
                List.of(), true);
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

    /** Reads the independent group owner timeline without creating generation work. */
    public GroupTopicTimelineResponse getGroupTopics(UUID userId, UUID sourceConversationId) {
        boolean admin = topicMapper.isAdmin(userId);
        if (!admin && !topicMapper.canAccessGroupOwner(sourceConversationId, userId)) {
            throw new AiTopicException("TOPIC_FORBIDDEN", false);
        }
        AiTopicOwnerService.OwnerRef owner = AiTopicOwnerService.group(sourceConversationId);
        InputBatch batch = inputService.collect(owner, null, java.util.Optional.empty());
        List<AiTopicEntity> topics = topicMapper.listReadyGroupTopics(sourceConversationId);
        AiTopicGenerationJobEntity job = jobMapper.findByOwnerFingerprint(owner.type(), owner.id(), batch.fingerprint());
        GenerationStatus status = topics.isEmpty() ? GenerationStatus.NOT_STARTED : GenerationStatus.READY;
        if (job != null && ("PENDING".equals(job.getStatus()) || "PROCESSING".equals(job.getStatus()) || "RETRY_WAIT".equals(job.getStatus()))) {
            status = GenerationStatus.GENERATING;
        } else if (job != null && ("FAILED".equals(job.getStatus()) || ("COMPLETED".equals(job.getStatus()) && topics.isEmpty()))) {
            status = GenerationStatus.FAILED;
        }
        return new GroupTopicTimelineResponse(sourceConversationId,
                new GenerationProjection(status, job == null ? null : job.getId(), job == null ? null : job.getLastErrorCode(), job == null ? null : job.getUpdatedAt()),
                topics.stream().map(this::project).toList(), false);
    }

    /** Creates work only from the owner quiet-window worker, never from a timeline read. */
    public void enqueueAutomaticGeneration(AiTopicOwnerService.OwnerRef owner) {
        InputBatch batch = inputService.collect(owner, null, java.util.Optional.empty());
        if (batch.items().isEmpty()) return;
        String jobKind = topicMapper.listReadyByOwner(owner.type(), owner.id()).isEmpty() ? "INITIAL" : "INCREMENTAL";
        UUID contactId = "CONTACT".equals(owner.type()) ? owner.id() : null;
        UUID groupConversationId = "WECOM_GROUP".equals(owner.type()) ? owner.id() : null;
        jobMapper.insertAutomaticIfAbsent(owner.type(), owner.id(), contactId, groupConversationId,
                jobKind, batch.fingerprint(), Instant.now());
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
        if (batch.items().isEmpty() && !contactHasSupportedChannel(contactId)) {
            return new GenerationProjection(GenerationStatus.NOT_STARTED, null, "WECOM_AI_UNSUPPORTED", Instant.now());
        }
        if (batch.items().isEmpty()) {
            return new GenerationProjection(GenerationStatus.NOT_STARTED, null, null, Instant.now());
        }
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
        AiTopicOwnerService.OwnerRef owner = ownerOf(job);
        boolean automatic = "AUTO".equals(job.getTriggerSource());
        if (!automatic && (userId == null || !userId.equals(job.getCreatedByUserId()))) {
            throw new AiTopicException("AI_AUTH_CONTEXT_MISSING", false);
        }
        InputBatch batch = inputService.collect(owner, automatic ? null : userId, java.util.Optional.empty());
        List<TopicContext> contexts = topicMapper.listReadyByOwner(owner.type(), owner.id()).stream().map(topic -> new TopicContext(topic.getId(), topic.getTitle(), topic.getConfirmedSummary() == null ? topic.getAiSummary() : topic.getConfirmedSummary(), topic.getFirstOccurredAt(), topic.getLastOccurredAt(), itemMapper.listByTopic(topic.getId()).stream().map(item -> item.getMessageId() != null ? item.getMessageId() : item.getCallRecordId() != null ? item.getCallRecordId() : item.getWecomMessageSummaryJobId()).filter(Objects::nonNull).toList())).toList();
        AiTopicModels.GenerationInput generationInput = new AiTopicModels.GenerationInput(owner, batch.items(), contexts,
                "INCREMENTAL".equals(job.getJobKind()));
        AiTopicGenerationAuditService.Context auditContext = generationAudit == null ? null : generationAudit.begin(job, generationInput, configHolder.get());
        var output = auditContext == null ? gateway.generate(generationInput) : gateway.generate(generationInput, auditContext);
        Map<UUID, AiTopicModels.SourceItem> byId = batch.items().stream().collect(java.util.stream.Collectors.toMap(AiTopicModels.SourceItem::id, x -> x));
        try {
          validateAssignments(output, byId, contexts);
          for (var assignment : output.assignments()) {
            List<AiTopicModels.SourceItem> sources = assignment.sourceIds().stream().map(byId::get).toList();
            ResolvedTopic resolved = resolveTopic(assignment.topicKey(), owner, sources, assignment.title(), assignment.summary(), assignment.relevance(), job.getInputFingerprint());
            AiTopicEntity topic = resolved.topic();
            boolean created = resolved.created();
            if (created) {
                int claimed = insertFirstSource(topic, sources.get(0));
                if (claimed == 0 && !moveArchivedMergedSource(topic, owner, sources.get(0))) {
                    topicMapper.deleteById(topic.getId());
                    continue;
                }
            }
            for (var source : sources.subList(created ? 1 : 0, sources.size())) {
                int inserted = itemMapper.insertIfAbsent(topic.getId(), source.sourceType() == SourceType.MESSAGE ? source.id() : null,
                        source.sourceType() == SourceType.CALL_RECORD ? source.id() : null,
                        source.sourceType() == SourceType.WECOM_SUMMARY ? source.id() : null,
                        source.occurredAt(), source.channelType());
                if (inserted == 0) moveArchivedMergedSource(topic, owner, source);
            }
            topic.setLastOccurredAt(sources.stream().map(AiTopicModels.SourceItem::occurredAt).max(Instant::compareTo).orElse(topic.getLastOccurredAt())); topic.setInputFingerprint(job.getInputFingerprint()); topic.setVersion(topic.getVersion() == null ? 1 : topic.getVersion() + 1); topicMapper.updateById(topic); versionMapper.insertVersion(topic.getId(), topic.getVersion(), "AI_GENERATED", topic.getTitle(), topic.getAiSummary(), "[]", null);
          }
          if (auditContext != null && generationAudit != null) generationAudit.outcome(auditContext, "BUSINESS_APPLY", "SUCCEEDED", null, null, null, safeJson(output), null, null, configHolder.get());
        } catch (AiTopicException e) {
          if (auditContext != null && generationAudit != null) {
            String stage = e.code().startsWith("AI_RESPONSE_") ? "RESPONSE_VALIDATE" : "BUSINESS_APPLY";
            generationAudit.outcome(auditContext, stage, "FAILED", null, null, null, safeJson(output), e.code(), e.diagnostic(), configHolder.get());
          }
          throw e;
        } catch (RuntimeException e) {
          if (auditContext != null && generationAudit != null) generationAudit.outcome(auditContext, "BUSINESS_APPLY", "FAILED", null, null, null, null, "AI_GENERATION_FAILED", e.getMessage(), configHolder.get());
          throw e;
        }
    }

    private String safeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); } catch (Exception ignored) { return "{}"; }
    }

    private void validateAssignments(AiTopicModels.GenerationOutput output,
                                     Map<UUID, AiTopicModels.SourceItem> sourcesById,
                                     List<TopicContext> readyContexts) {
        if (output == null || output.assignments() == null || output.assignments().isEmpty()) {
            throw new AiTopicException("AI_RESPONSE_INVALID", false, "ASSIGNMENTS_MISSING", null);
        }
        Set<UUID> readyTopicIds = readyContexts.stream().map(TopicContext::id).collect(java.util.stream.Collectors.toSet());
        Set<UUID> assigned = new HashSet<>();
        for (AiTopicModels.TopicAssignment assignment : output.assignments()) {
            if (assignment == null || assignment.topicKey() == null || assignment.topicKey().isBlank()
                    || assignment.title() == null || assignment.title().isBlank()
                    || assignment.summary() == null || assignment.summary().isBlank()
                    || assignment.relevance() < 0 || assignment.relevance() > 1
                    || assignment.sourceIds() == null || assignment.sourceIds().isEmpty()) {
                throw new AiTopicException("AI_RESPONSE_INVALID", false, "ASSIGNMENT_STRUCTURE_INVALID", null);
            }
            try {
                UUID referencedTopicId = UUID.fromString(assignment.topicKey());
                if (assignment.relevance() < configHolder.get().matchThreshold() || !readyTopicIds.contains(referencedTopicId)) {
                    throw new AiTopicException("AI_RESPONSE_INVALID", false, "TOPIC_REFERENCE_OUT_OF_SCOPE", null);
                }
            } catch (IllegalArgumentException ignored) {
                // Non-UUID keys identify newly allocated topics.
            }
            for (UUID sourceId : assignment.sourceIds()) {
                if (sourceId == null || !sourcesById.containsKey(sourceId) || !assigned.add(sourceId)) {
                    throw new AiTopicException("AI_RESPONSE_INVALID", false, "SOURCE_ASSIGNMENT_INVALID", null);
                }
            }
        }
        if (!assigned.equals(sourcesById.keySet())) {
            throw new AiTopicException("AI_RESPONSE_INVALID", false, "SOURCE_ASSIGNMENT_INCOMPLETE", null);
        }
    }

    private int insertFirstSource(AiTopicEntity topic, AiTopicModels.SourceItem source) {
        return itemMapper.insertIfAbsent(topic.getId(), source.sourceType() == SourceType.MESSAGE ? source.id() : null,
                source.sourceType() == SourceType.CALL_RECORD ? source.id() : null,
                source.sourceType() == SourceType.WECOM_SUMMARY ? source.id() : null,
                source.occurredAt(), source.channelType());
    }

    private boolean moveArchivedMergedSource(AiTopicEntity targetTopic,
                                             AiTopicOwnerService.OwnerRef owner,
                                             AiTopicModels.SourceItem source) {
        if (!"CONTACT".equals(owner.type())) return false;
        return itemMapper.moveArchivedMergedSourceToTopic(targetTopic.getId(), owner.id(),
                source.sourceType().name(), source.id()) > 0;
    }

    private ResolvedTopic resolveTopic(String key, AiTopicOwnerService.OwnerRef owner, List<AiTopicModels.SourceItem> sources, String title, String summary, double relevance, String inputFingerprint) {
        if (relevance >= configHolder.get().matchThreshold()) {
            try { AiTopicEntity existing = topicMapper.selectById(UUID.fromString(key)); if (existing != null && owner.type().equals(existing.getOwnerType()) && owner.id().equals(existing.getOwnerId()) && "READY".equals(existing.getStatus())) return new ResolvedTopic(existing, false); } catch (IllegalArgumentException ignored) {}
        }
        AiTopicEntity topic = new AiTopicEntity(); topic.setId(UUID.randomUUID()); topic.setOwnerType(owner.type()); topic.setOwnerId(owner.id()); topic.setContactId("CONTACT".equals(owner.type()) ? owner.id() : null); topic.setWecomGroupSourceConversationId("WECOM_GROUP".equals(owner.type()) ? owner.id() : null); topic.setTitle(title); topic.setAiSummary(summary); topic.setStatus("READY"); topic.setFirstOccurredAt(sources.stream().map(AiTopicModels.SourceItem::occurredAt).min(Instant::compareTo).orElse(Instant.now())); topic.setLastOccurredAt(sources.stream().map(AiTopicModels.SourceItem::occurredAt).max(Instant::compareTo).orElse(Instant.now())); topic.setVersion(1L); topic.setInputFingerprint(inputFingerprint); topicMapper.insert(topic); return new ResolvedTopic(topic, true);
    }

    private static AiTopicOwnerService.OwnerRef ownerOf(AiTopicGenerationJobEntity job) {
        if (job.getOwnerType() == null || job.getOwnerId() == null) {
            if (job.getContactId() == null) throw new AiTopicException("AI_TOPIC_OWNER_INVALID", false);
            return AiTopicOwnerService.contact(job.getContactId());
        }
        return new AiTopicOwnerService.OwnerRef(job.getOwnerType(), job.getOwnerId());
    }

    private record ResolvedTopic(AiTopicEntity topic, boolean created) {}

    public TopicOperationProjection submitOperation(UUID userId, UUID contactId, TopicOperationKind kind,
                                                    String requestPayload, String expectedVersions,
                                                    String idempotencyKey) {
        contactService.getById(userId, contactId);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new AiTopicException("IDEMPOTENCY_KEY_INVALID", false);
        }
        AiTopicOperationJobEntity job = enqueueOperation(userId, AiTopicOwnerService.contact(contactId), kind,
                requestPayload, expectedVersions, idempotencyKey);
        return operationProjection(job);
    }

    public TopicOperationProjection submitStore(UUID userId, UUID topicId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        AiTopicEntity topic = requireTopic(userId, topicId);
        if (!"READY".equals(topic.getStatus())) throw new AiTopicException("TOPIC_STORE_NOT_READY", false);
        AiTopicOwnerService.OwnerRef owner = ownerOf(topic);
        if ("CONTACT".equals(owner.type())) {
            return operationProjection(enqueueOperation(userId, owner, TopicOperationKind.STORE,
                    "{\"topicId\":\"" + topicId + "\"}", "{}", idempotencyKey));
        }
        if (inboxRequestMapper == null) throw new AiTopicException("TOPIC_INBOX_UNAVAILABLE", false);
        inboxRequestMapper.insertPendingIfAbsent(topicId, userId);
        var request = inboxRequestMapper.findPendingByTopicId(topicId);
        if (request == null) throw new AiTopicException("TOPIC_STORE_REQUEST_CONFLICT", false);
        return new TopicOperationProjection(request.getId(), TopicOperationKind.STORE,
                TopicOperationStatus.PENDING, null, request.getCreatedAt(), null);
    }

    @Transactional
    public TopicOperationProjection approveStore(UUID adminId, UUID requestId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        requireAdmin(adminId);
        if (inboxRequestMapper == null) throw new AiTopicException("TOPIC_INBOX_UNAVAILABLE", false);
        var request = inboxRequestMapper.selectById(requestId);
        if (request == null) throw new AiTopicException("TOPIC_STORE_REQUEST_NOT_FOUND", false);
        AiTopicEntity topic = topicMapper.selectById(request.getTopicId());
        if (topic == null || !"WECOM_GROUP".equals(topic.getOwnerType()) || !"READY".equals(topic.getStatus())) {
            throw new AiTopicException("TOPIC_STORE_REQUEST_INVALID", false);
        }
        if ("PENDING".equals(request.getStatus())) inboxRequestMapper.approvePending(requestId, adminId);
        request = inboxRequestMapper.selectById(requestId);
        if (request == null || !"APPROVED".equals(request.getStatus())) throw new AiTopicException("TOPIC_STORE_REQUEST_CONFLICT", false);
        AiTopicOperationJobEntity job = enqueueOperation(adminId, ownerOf(topic), TopicOperationKind.STORE,
                "{\"topicId\":\"" + topic.getId() + "\",\"approvalRequestId\":\"" + requestId + "\"}",
                "{}", idempotencyKey);
        return operationProjection(job);
    }

    @Transactional
    public TopicOperationProjection rejectStore(UUID adminId, UUID requestId, String reason, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        requireAdmin(adminId);
        if (inboxRequestMapper == null) throw new AiTopicException("TOPIC_INBOX_UNAVAILABLE", false);
        var request = inboxRequestMapper.selectById(requestId);
        if (request == null) throw new AiTopicException("TOPIC_STORE_REQUEST_NOT_FOUND", false);
        if (!"PENDING".equals(request.getStatus())) throw new AiTopicException("TOPIC_STORE_REQUEST_CONFLICT", false);
        AiTopicEntity topic = topicMapper.selectById(request.getTopicId());
        if (topic == null || !"WECOM_GROUP".equals(topic.getOwnerType())) {
            throw new AiTopicException("TOPIC_STORE_REQUEST_INVALID", false);
        }
        return operationProjection(enqueueOperation(adminId, ownerOf(topic), TopicOperationKind.REJECT_STORE,
                operationPayload(Map.of("requestId", requestId.toString(), "reason", reason == null ? "" : reason)),
                "{}", idempotencyKey));
    }

    public TopicOperationProjection submitRestore(UUID userId, UUID topicId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        AiTopicEntity topic = requireTopic(userId, topicId);
        if (!"STORED".equals(topic.getStatus())) throw new AiTopicException("TOPIC_RESTORE_NOT_READY", false);
        if ("WECOM_GROUP".equals(topic.getOwnerType())) requireAdmin(userId);
        return operationProjection(enqueueOperation(userId, ownerOf(topic), TopicOperationKind.RESTORE,
                "{\"topicId\":\"" + topicId + "\"}", "{}", idempotencyKey));
    }

    private AiTopicOperationJobEntity enqueueOperation(UUID userId, AiTopicOwnerService.OwnerRef owner,
                                                        TopicOperationKind kind, String requestPayload,
                                                        String expectedVersions, String idempotencyKey) {
        UUID contactId = "CONTACT".equals(owner.type()) ? owner.id() : null;
        UUID groupConversationId = "WECOM_GROUP".equals(owner.type()) ? owner.id() : null;
        return operationJobMapper.findOrCreate(contactId, owner.type(), owner.id(), groupConversationId, userId,
                kind.name(), requestPayload == null ? "{}" : requestPayload,
                expectedVersions == null ? "{}" : expectedVersions, idempotencyKey, Instant.now());
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new AiTopicException("IDEMPOTENCY_KEY_INVALID", false);
        }
    }

    private String operationPayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new AiTopicException("TOPIC_OPERATION_INVALID", false, e);
        }
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
            if (kind == TopicOperationKind.REJECT_STORE) {
                applyRejectStore(actor, payload);
            } else if (kind == TopicOperationKind.STORE || kind == TopicOperationKind.RESTORE || kind == TopicOperationKind.EDIT) {
                UUID topicId = UUID.fromString(String.valueOf(payload.get("topicId")));
                AiTopicEntity topic = requireTopic(actor, topicId);
                if (kind == TopicOperationKind.STORE) {
                    if ("WECOM_GROUP".equals(topic.getOwnerType())) ensureApprovedGroupStore(actor, topicId, payload);
                    if (topicMapper.transitionStatus(topicId, "READY", "STORED") == 0) throw new AiTopicException("TOPIC_OPERATION_CONFLICT", false);
                    versionMapper.insertVersion(topicId, topic.getVersion() + 1, "STORED", topic.getTitle(), topic.getAiSummary(), "[]", actor);
                } else if (kind == TopicOperationKind.RESTORE) {
                    if ("WECOM_GROUP".equals(topic.getOwnerType())) requireAdmin(actor);
                    if (topicMapper.transitionStatus(topicId, "STORED", "READY") == 0) throw new AiTopicException("TOPIC_OPERATION_CONFLICT", false);
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

    private void applyRejectStore(UUID actor, Map<String, Object> payload) {
        requireAdmin(actor);
        if (inboxRequestMapper == null || payload.get("requestId") == null) {
            throw new AiTopicException("TOPIC_STORE_REQUEST_INVALID", false);
        }
        UUID requestId = UUID.fromString(String.valueOf(payload.get("requestId")));
        var request = inboxRequestMapper.selectById(requestId);
        if (request == null || !"PENDING".equals(request.getStatus())) {
            throw new AiTopicException("TOPIC_STORE_REQUEST_CONFLICT", false);
        }
        AiTopicEntity topic = topicMapper.selectById(request.getTopicId());
        if (topic == null || !"WECOM_GROUP".equals(topic.getOwnerType())) {
            throw new AiTopicException("TOPIC_STORE_REQUEST_INVALID", false);
        }
        String reason = String.valueOf(payload.getOrDefault("reason", ""));
        if (inboxRequestMapper.rejectPending(requestId, actor, reason) == 0) {
            throw new AiTopicException("TOPIC_STORE_REQUEST_CONFLICT", false);
        }
    }

    private void ensureApprovedGroupStore(UUID actor, UUID topicId, Map<String, Object> payload) {
        requireAdmin(actor);
        if (inboxRequestMapper == null || payload.get("approvalRequestId") == null) throw new AiTopicException("TOPIC_STORE_APPROVAL_REQUIRED", false);
        UUID requestId = UUID.fromString(String.valueOf(payload.get("approvalRequestId")));
        var request = inboxRequestMapper.selectById(requestId);
        if (request == null || !topicId.equals(request.getTopicId()) || !"APPROVED".equals(request.getStatus())
                || !actor.equals(request.getReviewedByUserId())) throw new AiTopicException("TOPIC_STORE_APPROVAL_REQUIRED", false);
    }

    void publishSnapshotCompleted() { if (eventHub != null) eventHub.publish("topic-snapshot-completed", "{}"); }

    public List<TopicInboxRequestProjection> listPendingStoreRequests(UUID userId) {
        requireAdmin(userId);
        if (inboxRequestMapper == null) throw new AiTopicException("TOPIC_INBOX_UNAVAILABLE", false);
        return inboxRequestMapper.listPendingGroupStoreRequests().stream()
                .map(request -> new TopicInboxRequestProjection(request.getId(), request.getTopicId(),
                        request.getTopicTitle(), AiTopicModels.OwnerType.valueOf(request.getOwnerType()),
                        request.getOwnerId(), request.getOwnerLabel(), request.getRequestedByUserId(),
                        request.getCreatedAt()))
                .toList();
    }

    public IPage<TopicProjection> listStored(UUID userId, String search, int page, int size) {
        return listStored(userId, search, null, page, size);
    }

    public IPage<TopicProjection> listStored(UUID userId, String search, String ownerType, int page, int size) {
        Page<AiTopicEntity> query = new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size)));
        boolean admin = topicMapper.isAdmin(userId);
        String normalizedOwnerType = null;
        if (ownerType != null && !ownerType.isBlank()) {
            try {
                normalizedOwnerType = AiTopicModels.OwnerType.valueOf(ownerType.trim().toUpperCase()).name();
            } catch (IllegalArgumentException invalid) {
                throw new AiTopicException("TOPIC_OWNER_TYPE_INVALID", false);
            }
        }
        IPage<AiTopicEntity> result = topicMapper.listStoredForUser(query, userId, search, normalizedOwnerType, admin);
        Page<TopicProjection> output = new Page<>(query.getCurrent(), query.getSize());
        output.setTotal(result.getTotal());
        output.setRecords(result.getRecords().stream().map(this::project).toList());
        return output;
    }

    public List<com.crmforlogistics.messagecenter.entity.AiTopicGenerationAttemptEntity> listGenerationAttempts(UUID userId, UUID contactId, int limit) {
        contactService.getById(userId, contactId);
        if (attemptMapper == null) return List.of();
        return attemptMapper.listByContact(contactId, Math.min(100, Math.max(1, limit)));
    }

    private AiTopicEntity requireTopic(UUID userId, UUID topicId) {
        AiTopicEntity contactTopic = topicMapper.findContactTopicByIdAndOwner(topicId, userId);
        if (contactTopic != null) return contactTopic;

        AiTopicEntity topic = topicMapper.findGroupTopicById(topicId);
        if (topic == null) throw new AiTopicException("TOPIC_NOT_FOUND", false);
        if ("WECOM_GROUP".equals(topic.getOwnerType())) {
            boolean admin = topicMapper.isAdmin(userId);
            if (!admin && !topicMapper.canAccessGroupTopic(topicId, userId, false)) throw new AiTopicException("TOPIC_FORBIDDEN", false);
        } else {
            throw new AiTopicException("TOPIC_NOT_FOUND", false);
        }
        return topic;
    }

    private AiTopicOwnerService.OwnerRef ownerOf(AiTopicEntity topic) {
        if (topic.getOwnerType() == null || topic.getOwnerId() == null) {
            if (topic.getContactId() == null) throw new AiTopicException("AI_TOPIC_OWNER_INVALID", false);
            return AiTopicOwnerService.contact(topic.getContactId());
        }
        return new AiTopicOwnerService.OwnerRef(topic.getOwnerType(), topic.getOwnerId());
    }

    private void requireAdmin(UUID userId) {
        if (!topicMapper.isAdmin(userId)) throw new AiTopicException("TOPIC_ADMIN_REQUIRED", false);
    }

    private TopicProjection project(AiTopicEntity topic) {
        List<AiTopicItemEntity> items = itemMapper.listByTopic(topic.getId());
        List<String> channels = items.stream().map(AiTopicItemEntity::getChannelType).distinct().toList();
        List<TopicSourceItem> sources = items.stream().map(i -> {
            if (i.getMessageId() != null) return new TopicSourceItem(i.getMessageId(), SourceType.MESSAGE, i.getOccurredAt(), i.getChannelType());
            if (i.getCallRecordId() != null) return new TopicSourceItem(i.getCallRecordId(), SourceType.CALL_RECORD, i.getOccurredAt(), i.getChannelType());
            return new TopicSourceItem(i.getWecomMessageSummaryJobId(), SourceType.WECOM_SUMMARY, i.getOccurredAt(), i.getChannelType());
        }).toList();
        AiTopicModels.OwnerType ownerType = AiTopicModels.OwnerType.valueOf(topic.getOwnerType() == null ? "CONTACT" : topic.getOwnerType());
        boolean referencedGroup = ownerType == AiTopicModels.OwnerType.WECOM_GROUP;
        String ownerLabel = referencedGroup ? topic.getOwnerLabel() : topic.getContactDisplayName();
        return new TopicProjection(topic.getId(), topic.getTitle(), topic.getConfirmedSummary() == null ? topic.getAiSummary() : topic.getConfirmedSummary(), topic.getConfirmedSummary() == null ? "AI" : "EMPLOYEE", topic.getFirstOccurredAt(), topic.getLastOccurredAt(), channels, sources.size(), sources, topic.getVersion(), topic.getContactId(), topic.getContactDisplayName(), topic.getContactRemark(), topic.getContactChannelType(), topic.getContactChannelNickname(), ownerType, topic.getOwnerId(), ownerLabel, referencedGroup);
    }

    private boolean contactHasWeCom(UUID contactId) {
        return contactIdentityMapper.findByContactId(contactId).stream().anyMatch(i -> "wecom".equalsIgnoreCase(i.getChannelType()));
    }

    private boolean contactHasSupportedChannel(UUID contactId) {
        return contactIdentityMapper.findByContactId(contactId).stream()
                .map(com.crmforlogistics.messagecenter.entity.ContactIdentityEntity::getChannelType)
                .anyMatch(channel -> channel != null && (
                        "chatapp".equalsIgnoreCase(channel)
                                || "email".equalsIgnoreCase(channel)
                                || "phone".equalsIgnoreCase(channel)
                                || "wecom".equalsIgnoreCase(channel)));
    }
}
