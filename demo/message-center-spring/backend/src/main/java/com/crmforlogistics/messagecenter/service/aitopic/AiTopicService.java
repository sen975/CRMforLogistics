package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.entity.AiTopicGenerationJobEntity;
import com.crmforlogistics.messagecenter.entity.AiTopicItemEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import com.crmforlogistics.messagecenter.dto.request.AiTopicManualReviewRequest;
import com.crmforlogistics.messagecenter.dto.request.AiTopicFusionRequest;
import com.crmforlogistics.messagecenter.dto.response.AiTopicManualReviewResponse;
import com.crmforlogistics.messagecenter.mapper.AiTopicReviewMapper;

@Service
public class AiTopicService implements AiTopicSplitReconciler, AiTopicContactMergeReconciler {
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
    private final AiTopicReviewMapper reviewMapper;
    private final TopicAiGateway topicAiGateway;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

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

    public AiTopicService(ContactService contactService, AiTopicInputService inputService,
                          AiTopicMapper topicMapper, AiTopicItemMapper itemMapper,
                          AiTopicGenerationJobMapper jobMapper, AiTopicVersionMapper versionMapper,
                          AiTopicConfigHolder configHolder, ContactIdentityMapper contactIdentityMapper,
                          AiTopicOperationJobMapper operationJobMapper, AiTopicInboxRequestMapper inboxRequestMapper,
                          EventHub eventHub, AiTopicGenerationAuditService generationAudit,
                          com.crmforlogistics.messagecenter.mapper.AiTopicGenerationAttemptMapper attemptMapper,
                          AiTopicReviewMapper reviewMapper) {
        this(contactService, inputService, topicMapper, itemMapper, jobMapper, versionMapper, configHolder,
                contactIdentityMapper, operationJobMapper, inboxRequestMapper, eventHub, generationAudit,
                attemptMapper, reviewMapper, null);
    }

    @Autowired
    public AiTopicService(ContactService contactService, AiTopicInputService inputService,
                          AiTopicMapper topicMapper, AiTopicItemMapper itemMapper,
                          AiTopicGenerationJobMapper jobMapper, AiTopicVersionMapper versionMapper,
                          AiTopicConfigHolder configHolder, ContactIdentityMapper contactIdentityMapper,
                          AiTopicOperationJobMapper operationJobMapper, AiTopicInboxRequestMapper inboxRequestMapper,
                          EventHub eventHub, AiTopicGenerationAuditService generationAudit,
                          com.crmforlogistics.messagecenter.mapper.AiTopicGenerationAttemptMapper attemptMapper,
                          AiTopicReviewMapper reviewMapper, TopicAiGateway topicAiGateway) {
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
        this.reviewMapper = reviewMapper;
        this.topicAiGateway = topicAiGateway;
    }

    public AiTopicService(ContactService contactService, AiTopicInputService inputService,
                          AiTopicMapper topicMapper, AiTopicItemMapper itemMapper,
                          AiTopicGenerationJobMapper jobMapper, AiTopicVersionMapper versionMapper,
                          AiTopicConfigHolder configHolder, ContactIdentityMapper contactIdentityMapper) {
        this(contactService, inputService, topicMapper, itemMapper, jobMapper, versionMapper, configHolder,
                contactIdentityMapper, null, null, null, null, null);
    }

    public AiTopicService(ContactService contactService, AiTopicInputService inputService,
                          AiTopicMapper topicMapper, AiTopicItemMapper itemMapper,
                          AiTopicGenerationJobMapper jobMapper, AiTopicVersionMapper versionMapper,
                          AiTopicConfigHolder configHolder, ContactIdentityMapper contactIdentityMapper,
                          AiTopicOperationJobMapper operationJobMapper, AiTopicInboxRequestMapper inboxRequestMapper,
                          EventHub eventHub, AiTopicGenerationAuditService generationAudit,
                          com.crmforlogistics.messagecenter.mapper.AiTopicGenerationAttemptMapper attemptMapper) {
        this(contactService, inputService, topicMapper, itemMapper, jobMapper, versionMapper, configHolder,
                contactIdentityMapper, operationJobMapper, inboxRequestMapper, eventHub, generationAudit,
                attemptMapper, null);
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

    public List<TopicProjection> getReviewPendingTopics(UUID userId, UUID contactId) {
        if (contactService != null) contactService.getById(userId, contactId);
        return topicMapper.listReviewPendingForContact(contactId).stream().map(this::project).toList();
    }

    /**
     * 单条话题的读取。
     *
     * <h2>它存在的理由是一条具体的静默损失</h2>
     * {@code AiTopicMapper.updateEmployee} 的 SQL 是
     * {@code set title=#{title}, confirmed_summary=#{confirmedSummary}} —— <b>无条件赋值</b>。
     * 也就是说「只改标题」这个动作如果带上 {@code confirmedSummary=null}，会把这个人已经
     * 人工确认过的摘要<b>一起清掉</b>，而调用方、用户、日志三边都不会有任何提示。
     *
     * <p>页面侧的调用方之所以没踩到，是因为它手上本来就有那一行（用户在看它）。
     * 助手工具不同：模型只报了「把标题改成 X」，其余字段得有人在动手前读一次才知道。
     *
     * <p>归属校验<b>复用</b> {@link #requireTopic}，不另写一份：那条查询同时覆盖
     * 「联系人的话题归我」与「企微群话题我有权访问」两种情况，照抄一遍必然先分叉再走偏。
     */
    public TopicProjection getTopic(UUID userId, UUID topicId) {
        return project(requireTopic(userId, topicId));
    }

    @Transactional
    public TopicProjection keepPending(UUID userId, UUID topicId) {
        AiTopicEntity topic = requireTopic(userId, topicId);
        if (!"REVIEW_PENDING".equals(topic.getStatus())) throw new AiTopicException("TOPIC_REVIEW_CONFLICT", false);
        if (topicMapper.transitionStatus(topicId, "REVIEW_PENDING", "READY") == 0) throw new AiTopicException("TOPIC_REVIEW_CONFLICT", false);
        topic.setStatus("READY"); topic.setVersion((topic.getVersion() == null ? 1L : topic.getVersion()) + 1L);
        return project(topic);
    }

    @Transactional
    public TopicProjection storePending(UUID userId, UUID topicId) {
        AiTopicEntity topic = requireTopic(userId, topicId);
        if (!"REVIEW_PENDING".equals(topic.getStatus())) throw new AiTopicException("TOPIC_REVIEW_CONFLICT", false);
        if (topicMapper.transitionStatus(topicId, "REVIEW_PENDING", "STORED") == 0) throw new AiTopicException("TOPIC_REVIEW_CONFLICT", false);
        topic.setStatus("STORED"); topic.setVersion((topic.getVersion() == null ? 1L : topic.getVersion()) + 1L);
        return project(topic);
    }

    /** Reads the independent group owner timeline without creating generation work. */
    public GroupTopicTimelineResponse getGroupTopics(UUID userId, UUID sourceConversationId) {
        requireGroupTopicAccess(userId, sourceConversationId);
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

    @Transactional
    public GenerationProjection retryGroupGeneration(UUID userId, UUID sourceConversationId) {
        requireGroupTopicAccess(userId, sourceConversationId);
        AiTopicOwnerService.OwnerRef owner = AiTopicOwnerService.group(sourceConversationId);
        InputBatch batch = inputService.collect(owner, null, java.util.Optional.empty());
        if (batch.items().isEmpty()) {
            return new GenerationProjection(GenerationStatus.NOT_STARTED, null, null, Instant.now());
        }
        AiTopicGenerationJobEntity job = jobMapper.findByOwnerFingerprint(owner.type(), owner.id(), batch.fingerprint());
        if (job != null && ("FAILED".equals(job.getStatus()) || "COMPLETED".equals(job.getStatus()))) {
            jobMapper.requeueTerminal(job.getId(), Instant.now());
        } else if (job == null) {
            String jobKind = topicMapper.listReadyByOwner(owner.type(), owner.id()).isEmpty() ? "INITIAL" : "INCREMENTAL";
            jobMapper.insertGroupRetryIfAbsent(sourceConversationId, userId, jobKind, batch.fingerprint(), Instant.now());
            job = jobMapper.findByOwnerFingerprint(owner.type(), owner.id(), batch.fingerprint());
        }
        return new GenerationProjection(GenerationStatus.GENERATING, job == null ? null : job.getId(), null, Instant.now());
    }

    private void requireGroupTopicAccess(UUID userId, UUID sourceConversationId) {
        boolean admin = topicMapper.isAdmin(userId);
        if (!admin && !topicMapper.canAccessGroupOwner(sourceConversationId, userId)) {
            throw new AiTopicException("TOPIC_FORBIDDEN", false);
        }
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
        List<AiTopicEntity> topics = requireFusionTopics(userId, topicIds, expectedVersions);
        AiTopicManualReviewResponse.Assignment fusion = generateFusionAssignment(topics);
        return List.of(applyFusion(userId, topics, expectedVersions, fusion.title(), fusion.summary()));
    }

    @Override
    @Transactional
    public void reconcileAfterContactMerge(UUID sourceContactId, UUID targetContactId, UUID userId) {
        topicMapper.transferReadyByContact(sourceContactId, targetContactId);
        topicMapper.transferReviewPendingByContact(sourceContactId, targetContactId);
        List<AiTopicEntity> candidates = topicMapper.listContactMergeCandidates(targetContactId);
        Map<String, List<AiTopicEntity>> groups = candidates.stream()
                .filter(topic -> "CONTACT".equals(normalizedOwnerType(topic)))
                .collect(Collectors.groupingBy(topic -> normalizeTitle(topic.getTitle()),
                        java.util.LinkedHashMap::new, Collectors.toList()));
        for (List<AiTopicEntity> group : groups.values()) {
            if (group.size() < 2) continue;
            List<AiTopicEntity> topics = group.stream()
                    .sorted(Comparator.comparing(AiTopicEntity::getFirstOccurredAt,
                            Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(AiTopicEntity::getId))
                    .toList();
            Map<UUID, Long> versions = topics.stream().collect(Collectors.toMap(
                    AiTopicEntity::getId, topic -> topic.getVersion() == null ? 1L : topic.getVersion()));
            if (sameCurrentContent(topics)) {
                AiTopicEntity first = topics.get(0);
                applyFusion(userId, topics, versions, first.getTitle().trim(), effectiveSummary(first),
                        topics.stream().map(AiTopicEntity::getConfirmedSummary)
                                .filter(summary -> summary != null && !summary.isBlank())
                                .map(String::trim).findFirst().orElse(null));
            } else {
                AiTopicManualReviewResponse.Assignment fusion = generateFusionAssignment(topics);
                applyFusion(userId, topics, versions, fusion.title(), fusion.summary());
            }
        }
    }

    private TopicProjection applyFusion(UUID userId, List<AiTopicEntity> topics,
                                        Map<UUID, Long> expectedVersions,
                                        String title, String summary) {
        return applyFusion(userId, topics, expectedVersions, title, summary, null);
    }

    private TopicProjection applyFusion(UUID userId, List<AiTopicEntity> topics,
                                        Map<UUID, Long> expectedVersions,
                                        String title, String summary, String confirmedSummary) {
        AiTopicEntity first = topics.get(0);
        String ownerType = normalizedOwnerType(first);
        UUID ownerId = normalizedOwnerId(first);
        List<AiTopicItemEntity> allItems = topics.stream().flatMap(topic -> itemMapper.listByTopic(topic.getId()).stream()).toList();
        if (allItems.isEmpty()) throw new AiTopicException("TOPIC_MERGE_INVALID", false);
        UUID mergedId = UUID.randomUUID();
        AiTopicEntity target = new AiTopicEntity();
        target.setId(mergedId);
        target.setOwnerType(ownerType);
        target.setOwnerId(ownerId);
        target.setContactId("CONTACT".equals(ownerType) ? ownerId : null);
        target.setWecomGroupSourceConversationId("WECOM_GROUP".equals(ownerType) ? ownerId : null);
        target.setTitle(title);
        target.setAiSummary(summary);
        target.setConfirmedSummary(confirmedSummary);
        target.setStatus("READY");
        target.setFirstOccurredAt(allItems.stream().map(AiTopicItemEntity::getOccurredAt).min(Instant::compareTo).orElse(first.getFirstOccurredAt()));
        target.setLastOccurredAt(allItems.stream().map(AiTopicItemEntity::getOccurredAt).max(Instant::compareTo).orElse(first.getLastOccurredAt()));
        target.setInputFingerprint(fusionFingerprint(topics));
        target.setVersion(1L);
        topicMapper.insert(target);
        for (AiTopicItemEntity item : allItems) { item.setTopicId(mergedId); itemMapper.updateById(item); }
        for (AiTopicEntity topic : topics) topicMapper.archiveForFusion(topic.getId());
        final String sourceTopicIds;
        try {
            sourceTopicIds = objectMapper.writeValueAsString(topics.stream().map(AiTopicEntity::getId).toList());
        } catch (Exception error) {
            throw new AiTopicException("TOPIC_MERGE_INVALID", false, error);
        }
        versionMapper.insertVersion(mergedId, 1L, "MERGED", target.getTitle(), target.getAiSummary(), sourceTopicIds, userId);
        String mergedTopicRef;
        try { mergedTopicRef = objectMapper.writeValueAsString(List.of(mergedId)); }
        catch (Exception error) { throw new AiTopicException("TOPIC_MERGE_INVALID", false, error); }
        for (AiTopicEntity topic : topics) {
            versionMapper.insertVersion(topic.getId(), (topic.getVersion() == null ? 1L : topic.getVersion()) + 1L,
                    "MERGED_INTO", topic.getTitle(), topic.getAiSummary(), mergedTopicRef, userId);
        }
        return project(target);
    }

    private boolean sameCurrentContent(List<AiTopicEntity> topics) {
        if (topics.isEmpty()) return false;
        String title = normalizeTitle(topics.get(0).getTitle());
        String summary = normalizeSummary(effectiveSummary(topics.get(0)));
        return topics.stream().allMatch(topic -> title.equals(normalizeTitle(topic.getTitle()))
                && summary.equals(normalizeSummary(effectiveSummary(topic))));
    }

    private String effectiveSummary(AiTopicEntity topic) {
        return topic.getConfirmedSummary() == null ? topic.getAiSummary() : topic.getConfirmedSummary();
    }

    private String normalizeTitle(String title) {
        return title == null ? "" : title.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String normalizeSummary(String summary) {
        return summary == null ? "" : summary.trim();
    }

    @Transactional
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
        if (job != null && ("FAILED".equals(job.getStatus()) || "COMPLETED".equals(job.getStatus()))) {
            jobMapper.requeueTerminal(job.getId(), Instant.now());
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
            if (created) {
                versionMapper.insertVersion(topic.getId(), topic.getVersion(), "AI_GENERATED",
                        topic.getTitle(), topic.getAiSummary(), "[]", null);
            } else {
                long expectedVersion = topic.getVersion() == null ? 1L : topic.getVersion();
                Instant firstOccurredAt = sources.stream().map(AiTopicModels.SourceItem::occurredAt)
                        .min(Instant::compareTo).filter(value -> topic.getFirstOccurredAt() == null
                                || value.isBefore(topic.getFirstOccurredAt())).orElse(topic.getFirstOccurredAt());
                Instant lastOccurredAt = sources.stream().map(AiTopicModels.SourceItem::occurredAt)
                        .max(Instant::compareTo).filter(value -> topic.getLastOccurredAt() == null
                                || value.isAfter(topic.getLastOccurredAt())).orElse(topic.getLastOccurredAt());
                if (topicMapper.updateAiGenerated(topic.getId(), assignment.title(), assignment.summary(),
                        firstOccurredAt, lastOccurredAt, job.getInputFingerprint(), expectedVersion) == 0) {
                    throw new AiTopicException("TOPIC_VERSION_CONFLICT", false);
                }
                topic.setTitle(assignment.title());
                topic.setAiSummary(assignment.summary());
                topic.setConfirmedSummary(null);
                topic.setFirstOccurredAt(firstOccurredAt);
                topic.setLastOccurredAt(lastOccurredAt);
                topic.setInputFingerprint(job.getInputFingerprint());
                topic.setVersion(expectedVersion + 1);
                versionMapper.insertVersion(topic.getId(), topic.getVersion(), "AI_GENERATED",
                        topic.getTitle(), topic.getAiSummary(), "[]", null);
            }
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

    @Override
    @Transactional
    public void recomputeAfterSourceSplit(UUID topicId) {
        if (reviewMapper == null || topicAiGateway == null || configHolder == null || versionMapper == null) {
            throw new AiTopicException("TOPIC_SPLIT_RECONCILE_UNAVAILABLE", false);
        }
        AiTopicEntity topic = topicMapper.selectById(topicId);
        if (topic == null || !"CONTACT".equals(normalizedOwnerType(topic)) || !"READY".equals(topic.getStatus())) {
            throw new AiTopicException("TOPIC_SPLIT_RECONCILE_INVALID", false);
        }
        AiTopicOwnerService.OwnerRef owner = AiTopicOwnerService.contact(normalizedOwnerId(topic));
        List<AiTopicModels.SourceItem> sources = AiTopicInputService.filterSupportedSources(
                reviewMapper.listTopicSources(List.of(topicId)).stream()
                        .map(this::sourceOption).map(this::manualReviewSource).toList());
        if (sources.isEmpty() || sources.size() > configHolder.get().maxInputRecords()
                || requiredJson(sources).getBytes(StandardCharsets.UTF_8).length > configHolder.get().maxInputBytes()) {
            throw new AiTopicException("TOPIC_SPLIT_RECONCILE_INPUT_INVALID", false);
        }
        TopicContext context = new TopicContext(topic.getId(), topic.getTitle(),
                topic.getConfirmedSummary() == null ? topic.getAiSummary() : topic.getConfirmedSummary(),
                topic.getFirstOccurredAt(), topic.getLastOccurredAt(),
                sources.stream().map(AiTopicModels.SourceItem::id).toList());
        AiTopicModels.GenerationOutput output = topicAiGateway.fuse(
                new AiTopicModels.GenerationInput(owner, sources, List.of(context), false));
        Set<UUID> expectedSourceIds = sources.stream().map(AiTopicModels.SourceItem::id)
                .collect(java.util.stream.Collectors.toSet());
        if (output == null || output.assignments() == null || output.assignments().size() != 1) {
            throw new AiTopicException("TOPIC_SPLIT_RECONCILE_RESPONSE_INVALID", false);
        }
        AiTopicModels.TopicAssignment assignment = output.assignments().get(0);
        if (assignment.title() == null || assignment.title().isBlank()
                || assignment.summary() == null || assignment.summary().isBlank()
                || assignment.sourceIds() == null
                || assignment.sourceIds().size() != expectedSourceIds.size()
                || !new HashSet<>(assignment.sourceIds()).equals(expectedSourceIds)) {
            throw new AiTopicException("TOPIC_SPLIT_RECONCILE_RESPONSE_INVALID", false);
        }
        Instant firstOccurredAt = sources.stream().map(AiTopicModels.SourceItem::occurredAt)
                .min(Instant::compareTo).orElseThrow();
        Instant lastOccurredAt = sources.stream().map(AiTopicModels.SourceItem::occurredAt)
                .max(Instant::compareTo).orElseThrow();
        String fingerprint = AiTopicInputService.fingerprint(owner, sources);
        long expectedVersion = topic.getVersion() == null ? 1L : topic.getVersion();
        if (topicMapper.updateAfterSplit(topicId, assignment.title(), assignment.summary(), firstOccurredAt,
                lastOccurredAt, fingerprint, expectedVersion) == 0) {
            throw new AiTopicException("TOPIC_VERSION_CONFLICT", false);
        }
        topic.setTitle(assignment.title());
        topic.setAiSummary(assignment.summary());
        topic.setConfirmedSummary(null);
        topic.setFirstOccurredAt(firstOccurredAt);
        topic.setLastOccurredAt(lastOccurredAt);
        topic.setInputFingerprint(fingerprint);
        topic.setVersion(expectedVersion + 1);
        versionMapper.insertVersion(topicId, topic.getVersion(), "AI_GENERATED", topic.getTitle(),
                topic.getAiSummary(), "[]", null);
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
        if (!("READY".equals(topic.getStatus()) || "REVIEW_PENDING".equals(topic.getStatus()))) throw new AiTopicException("TOPIC_STORE_NOT_READY", false);
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
        if (job == null) throw new AiTopicException("TOPIC_OPERATION_UNAVAILABLE", false);
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
                    String fromStatus = "REVIEW_PENDING".equals(topic.getStatus()) ? "REVIEW_PENDING" : "READY";
                    if (topicMapper.transitionStatus(topicId, fromStatus, "STORED") == 0) throw new AiTopicException("TOPIC_OPERATION_CONFLICT", false);
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

    @Transactional
    public AiTopicManualReviewResponse.FusionPreviewResponse previewTopicFusion(UUID userId, UUID contactId,
                                                                                 AiTopicFusionRequest request) {
        if (contactService != null) contactService.getById(userId, contactId);
        if (request == null || reviewMapper == null || topicAiGateway == null) {
            throw new AiTopicException("TOPIC_FUSION_UNAVAILABLE", false);
        }
        List<AiTopicEntity> topics = requireFusionTopics(userId, request.topicIds(), request.expectedVersions());
        if (topics.stream().anyMatch(topic -> !"CONTACT".equals(normalizedOwnerType(topic))
                || !contactId.equals(normalizedOwnerId(topic)))) {
            throw new AiTopicException("TOPIC_MERGE_INVALID", false);
        }
        AiTopicManualReviewResponse.Assignment result = generateFusionAssignment(topics);
        UUID previewId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(900);
        reviewMapper.insertFusionPreview(previewId, "CONTACT", contactId, requiredJson(request.topicIds()),
                requiredJson(result), requiredJson(request.expectedVersions()), userId, expiresAt);
        return new AiTopicManualReviewResponse.FusionPreviewResponse(previewId, List.copyOf(request.topicIds()),
                result.title(), result.summary(), result.sourceIds().size(),
                Map.copyOf(request.expectedVersions()), expiresAt);
    }

    @Transactional
    public TopicProjection applyTopicFusion(UUID userId, UUID contactId, UUID previewId,
                                            String idempotencyKey) {
        if (contactService != null) contactService.getById(userId, contactId);
        validateIdempotencyKey(idempotencyKey);
        if (reviewMapper == null) throw new AiTopicException("TOPIC_FUSION_UNAVAILABLE", false);
        AiTopicReviewMapper.FusionPreviewRow existing = reviewMapper.findFusionByIdempotency(userId, idempotencyKey);
        if (existing != null) {
            if (!previewId.equals(existing.id()) || existing.resultTopicId() == null) {
                throw new AiTopicException("TOPIC_FUSION_CONFLICT", false);
            }
            return project(requireTopic(userId, existing.resultTopicId()));
        }
        AiTopicReviewMapper.FusionPreviewRow preview = reviewMapper.findFusionPreview(previewId);
        if (preview == null || !"CONTACT".equals(preview.ownerType()) || !contactId.equals(preview.ownerId())
                || !userId.equals(preview.createdByUserId())) {
            throw new AiTopicException("TOPIC_FUSION_NOT_FOUND", false);
        }
        if (!"PENDING".equals(preview.status()) || preview.expiresAt() == null
                || !preview.expiresAt().isAfter(Instant.now())) {
            throw new AiTopicException("TOPIC_FUSION_EXPIRED", false);
        }
        List<UUID> topicIds = parseUuidList(preview.topicIds(), "TOPIC_FUSION_SNAPSHOT_INVALID");
        Map<UUID, Long> expectedVersions = parseExpectedVersions(preview.expectedVersions());
        AiTopicManualReviewResponse.Assignment result = parseFusionResult(preview.resultSnapshot());
        List<AiTopicEntity> topics = requireFusionTopics(userId, topicIds, expectedVersions);
        TopicProjection merged = applyFusion(userId, topics, expectedVersions, result.title(), result.summary());
        if (reviewMapper.markFusionApplied(previewId, merged.id(), idempotencyKey) == 0) {
            throw new AiTopicException("TOPIC_FUSION_CONFLICT", false);
        }
        return merged;
    }

    private List<AiTopicEntity> requireFusionTopics(UUID userId, List<UUID> topicIds,
                                                     Map<UUID, Long> expectedVersions) {
        if (topicIds == null || topicIds.size() < 2 || topicIds.size() > 20
                || topicIds.stream().anyMatch(Objects::isNull)
                || topicIds.stream().distinct().count() != topicIds.size()
                || expectedVersions == null || topicIds.stream().anyMatch(id -> !expectedVersions.containsKey(id))) {
            throw new AiTopicException("TOPIC_MERGE_INVALID", false);
        }
        List<AiTopicEntity> topics = topicIds.stream().map(id -> requireTopic(userId, id))
                .sorted(Comparator.comparing(AiTopicEntity::getFirstOccurredAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(AiTopicEntity::getId)).toList();
        AiTopicEntity first = topics.get(0);
        String ownerType = normalizedOwnerType(first);
        UUID ownerId = normalizedOwnerId(first);
        for (AiTopicEntity topic : topics) {
            if (!("READY".equals(topic.getStatus()) || "REVIEW_PENDING".equals(topic.getStatus()))
                    || !ownerType.equals(normalizedOwnerType(topic))
                    || !Objects.equals(ownerId, normalizedOwnerId(topic))) {
                throw new AiTopicException("TOPIC_MERGE_INVALID", false);
            }
            if (!Objects.equals(expectedVersions.get(topic.getId()), topic.getVersion())) {
                throw new AiTopicException("TOPIC_VERSION_CONFLICT", false);
            }
        }
        return topics;
    }

    private AiTopicManualReviewResponse.Assignment generateFusionAssignment(List<AiTopicEntity> topics) {
        if (reviewMapper == null || topicAiGateway == null || configHolder == null) {
            throw new AiTopicException("TOPIC_FUSION_UNAVAILABLE", false);
        }
        List<UUID> topicIds = topics.stream().map(AiTopicEntity::getId).toList();
        List<AiTopicModels.SourceItem> sources = reviewMapper.listTopicSources(topicIds).stream()
                .map(this::sourceOption).map(this::manualReviewSource).toList();
        if (sources.isEmpty() || sources.size() > configHolder.get().maxInputRecords()
                || requiredJson(sources).getBytes(StandardCharsets.UTF_8).length > configHolder.get().maxInputBytes()) {
            throw new AiTopicException("TOPIC_FUSION_INPUT_TOO_LARGE", false);
        }
        List<TopicContext> contexts = topics.stream().map(topic -> new TopicContext(topic.getId(), topic.getTitle(),
                topic.getConfirmedSummary() == null ? topic.getAiSummary() : topic.getConfirmedSummary(),
                topic.getFirstOccurredAt(), topic.getLastOccurredAt(), itemMapper.listByTopic(topic.getId()).stream()
                .map(item -> item.getMessageId() != null ? item.getMessageId()
                        : item.getCallRecordId() != null ? item.getCallRecordId() : item.getWecomMessageSummaryJobId())
                .filter(Objects::nonNull).toList())).toList();
        AiTopicModels.GenerationOutput output = topicAiGateway.fuse(new AiTopicModels.GenerationInput(
                new AiTopicOwnerService.OwnerRef(normalizedOwnerType(topics.get(0)), normalizedOwnerId(topics.get(0))),
                sources, contexts, false));
        Set<UUID> expectedSourceIds = sources.stream().map(AiTopicModels.SourceItem::id)
                .collect(java.util.stream.Collectors.toSet());
        if (output == null || output.assignments() == null || output.assignments().size() != 1) {
            throw new AiTopicException("TOPIC_FUSION_RESPONSE_INVALID", false);
        }
        AiTopicModels.TopicAssignment assignment = output.assignments().get(0);
        if (assignment.title() == null || assignment.title().isBlank() || assignment.summary() == null
                || assignment.summary().isBlank() || assignment.sourceIds() == null
                || !new HashSet<>(assignment.sourceIds()).equals(expectedSourceIds)
                || assignment.sourceIds().size() != expectedSourceIds.size()) {
            throw new AiTopicException("TOPIC_FUSION_RESPONSE_INVALID", false);
        }
        return new AiTopicManualReviewResponse.Assignment("fusion", assignment.title(), assignment.summary(),
                1d, List.copyOf(assignment.sourceIds()));
    }

    private AiTopicManualReviewResponse.Assignment parseFusionResult(String json) {
        try { return objectMapper.readValue(json, AiTopicManualReviewResponse.Assignment.class); }
        catch (Exception error) { throw new AiTopicException("TOPIC_FUSION_SNAPSHOT_INVALID", false, error); }
    }

    private List<UUID> parseUuidList(String json, String errorCode) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, UUID.class));
        } catch (Exception error) {
            throw new AiTopicException(errorCode, false, error);
        }
    }

    private String normalizedOwnerType(AiTopicEntity topic) {
        return topic.getOwnerType() == null ? "CONTACT" : topic.getOwnerType();
    }

    private UUID normalizedOwnerId(AiTopicEntity topic) {
        return topic.getOwnerId() == null ? topic.getContactId() : topic.getOwnerId();
    }

    public AiTopicManualReviewResponse.SourceListResponse listManualReviewSources(UUID userId, UUID contactId,
                                                                                    Instant from, Instant to) {
        return listManualReviewSources(userId, contactId, null, from, to);
    }

    public AiTopicManualReviewResponse.SourceListResponse listManualReviewSources(UUID userId, UUID contactId,
                                                                                    UUID contactIdentityId,
                                                                                    Instant from, Instant to) {
        if (contactService != null) contactService.getById(userId, contactId);
        if (reviewMapper == null) throw new AiTopicException("TOPIC_REVIEW_UNAVAILABLE", false);
        List<AiTopicManualReviewResponse.SourceOption> queried = reviewMapper
                .listSources(contactId, contactIdentityId, from, to, 201).stream()
                .map(this::sourceOption).toList();
        boolean hasMore = queried.size() > 200;
        List<AiTopicManualReviewResponse.SourceOption> options = hasMore ? queried.subList(0, 200) : queried;
        List<ContactIdentityEntity> identities =
                contactIdentityMapper == null ? List.of() : contactIdentityMapper.findByContactId(contactId);
        boolean hasWeCom = identities.stream().anyMatch(identity ->
                "wecom".equalsIgnoreCase(identity.getChannelType()));
        boolean requestedWeCom = contactIdentityId == null || identities.stream().anyMatch(identity ->
                contactIdentityId.equals(identity.getId()) && "wecom".equalsIgnoreCase(identity.getChannelType()));
        boolean hasWeComSummary = options.stream().anyMatch(option -> "wecom".equalsIgnoreCase(option.channelType()) && option.selectable());
        return new AiTopicManualReviewResponse.SourceListResponse(contactId, List.copyOf(options), hasMore,
                requestedWeCom && hasWeCom && !hasWeComSummary ? "WECOM_SUMMARY_NOT_COMPLETED" : null);
    }

    @Transactional
    public AiTopicManualReviewResponse.PreviewResponse previewManualReview(UUID userId, UUID contactId,
                                                                             AiTopicManualReviewRequest request) {
        if (contactService != null) contactService.getById(userId, contactId);
        if (reviewMapper == null || topicAiGateway == null || request == null || request.contactIdentityId() == null
                || request.sourceIds() == null || request.sourceIds().isEmpty()
                || (request.from() != null && request.to() != null && !request.from().isBefore(request.to()))
                || request.sourceIds().size() > 200 || request.sourceIds().stream().anyMatch(Objects::isNull)
                || request.sourceIds().stream().distinct().count() != request.sourceIds().size()) {
            throw new AiTopicException("TOPIC_REVIEW_INVALID", false);
        }
        List<AiTopicManualReviewResponse.SourceOption> available = reviewMapper
                .listSources(contactId, request.contactIdentityId(), request.from(), request.to(), 201).stream()
                .map(this::sourceOption).toList();
        Map<UUID, AiTopicManualReviewResponse.SourceOption> byId = available.stream()
                .collect(java.util.stream.Collectors.toMap(AiTopicManualReviewResponse.SourceOption::id, x -> x));
        List<AiTopicManualReviewResponse.SourceOption> selected = request.sourceIds().stream().map(byId::get).toList();
        if (selected.stream().anyMatch(source -> source == null || !source.selectable())) {
            throw new AiTopicException("TOPIC_REVIEW_SOURCE_NOT_AVAILABLE", false);
        }
        String fingerprint = manualReviewFingerprint(selected);
        if (request.sourceFingerprint() != null && !request.sourceFingerprint().isBlank()
                && !MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.UTF_8), request.sourceFingerprint().getBytes(StandardCharsets.UTF_8))) {
            throw new AiTopicException("TOPIC_REVIEW_SNAPSHOT_CONFLICT", false);
        }
        List<TopicContext> contexts = readyTopicContexts(contactId);
        List<AiTopicModels.SourceItem> sources = selected.stream().map(this::manualReviewSource).toList();
        AiTopicModels.GenerationOutput output = topicAiGateway.generate(new AiTopicModels.GenerationInput(
                AiTopicOwnerService.contact(contactId), sources, contexts, !contexts.isEmpty()));
        Map<UUID, AiTopicModels.SourceItem> bySourceId = sources.stream()
                .collect(java.util.stream.Collectors.toMap(AiTopicModels.SourceItem::id, source -> source));
        validateAssignments(output, bySourceId, contexts);
        List<AiTopicManualReviewResponse.Assignment> assignments = output.assignments().stream()
                .map(assignment -> new AiTopicManualReviewResponse.Assignment(assignment.topicKey(), assignment.title(),
                        assignment.summary(), assignment.relevance(), assignment.sourceIds()))
                .toList();
        Map<UUID, Long> expectedVersions = topicMapper.listReadyByOwner("CONTACT", contactId).stream()
                .collect(java.util.stream.Collectors.toMap(AiTopicEntity::getId, AiTopicEntity::getVersion));
        UUID previewId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(900);
        reviewMapper.insertPreview(previewId, contactId, fingerprint, requiredJson(selected), requiredJson(assignments),
                requiredJson(expectedVersions), request.from(), request.to(), userId, expiresAt);
        return new AiTopicManualReviewResponse.PreviewResponse(previewId, contactId, fingerprint,
                assignments, expectedVersions, expiresAt);
    }

    @Transactional
    public AiTopicManualReviewResponse.ApplyResponse applyManualReview(UUID userId, UUID contactId, UUID previewId,
                                                                        AiTopicManualReviewRequest request,
                                                                        String idempotencyKey) {
        if (contactService != null) contactService.getById(userId, contactId);
        validateIdempotencyKey(idempotencyKey);
        if (reviewMapper == null) throw new AiTopicException("TOPIC_REVIEW_UNAVAILABLE", false);
        AiTopicReviewMapper.PreviewRow existingByKey = reviewMapper.findByIdempotency(userId, idempotencyKey);
        if (existingByKey != null) {
            if (!previewId.equals(existingByKey.id())) throw new AiTopicException("TOPIC_REVIEW_CONFLICT", false);
            return appliedResponse(existingByKey);
        }
        AiTopicReviewMapper.PreviewRow preview = reviewMapper.findPreview(previewId);
        if (preview == null || !contactId.equals(preview.contactId()) || !userId.equals(preview.createdByUserId())) {
            throw new AiTopicException("TOPIC_REVIEW_NOT_FOUND", false);
        }
        if (!"PENDING".equals(preview.status()) || preview.expiresAt() == null || !preview.expiresAt().isAfter(Instant.now())) {
            throw new AiTopicException("TOPIC_REVIEW_EXPIRED", false);
        }
        String requestedFingerprint = request == null ? null : request.sourceFingerprint();
        if (requestedFingerprint != null && !requestedFingerprint.isBlank() && !requestedFingerprint.equals(preview.sourceFingerprint())) {
            throw new AiTopicException("TOPIC_REVIEW_SNAPSHOT_CONFLICT", false);
        }
        List<AiTopicManualReviewResponse.SourceOption> selected;
        try {
            selected = objectMapper.readValue(preview.sourceSnapshot(), objectMapper.getTypeFactory().constructCollectionType(List.class, AiTopicManualReviewResponse.SourceOption.class));
        } catch (Exception e) {
            throw new AiTopicException("TOPIC_REVIEW_SNAPSHOT_INVALID", false, e);
        }
        if (selected.isEmpty()) throw new AiTopicException("TOPIC_REVIEW_INVALID", false);
        UUID identityId = selected.get(0).contactIdentityId();
        if (identityId == null || selected.stream().anyMatch(source -> !identityId.equals(source.contactIdentityId()))) {
            throw new AiTopicException("TOPIC_REVIEW_SOURCE_SCOPE_CONFLICT", false);
        }
        List<AiTopicManualReviewResponse.SourceOption> current = reviewMapper
                .listSourcesByIds(contactId, identityId, selected.stream().map(AiTopicManualReviewResponse.SourceOption::id).toList())
                .stream().map(this::sourceOption).toList();
        if (current.size() != selected.size() || current.stream().anyMatch(source -> !source.selectable())
                || !preview.sourceFingerprint().equals(manualReviewFingerprint(current))) {
            throw new AiTopicException("TOPIC_REVIEW_SNAPSHOT_CONFLICT", false);
        }
        Map<UUID, Long> expectedVersions = parseExpectedVersions(preview.expectedVersions());
        List<AiTopicManualReviewResponse.Assignment> assignments = request != null && request.assignments() != null
                && !request.assignments().isEmpty()
                ? request.assignments().stream().map(assignment -> new AiTopicManualReviewResponse.Assignment(
                        assignment.topicKey(), assignment.title(), assignment.summary(), assignment.relevance(), assignment.sourceIds())).toList()
                : parseAssignments(preview.assignmentSnapshot());
        validateManualAssignments(assignments, current, expectedVersions);
        Map<UUID, AiTopicManualReviewResponse.SourceOption> sourcesById = current.stream()
                .collect(java.util.stream.Collectors.toMap(AiTopicManualReviewResponse.SourceOption::id, source -> source));
        List<UUID> resultTopicIds = new ArrayList<>();
        Set<UUID> sourceTopicsToRecompute = new LinkedHashSet<>();
        for (AiTopicManualReviewResponse.Assignment assignment : assignments) {
            List<AiTopicManualReviewResponse.SourceOption> assignedSources = assignment.sourceIds().stream()
                    .map(sourcesById::get).toList();
            UUID targetTopicId = applyManualAssignment(userId, contactId, preview.sourceFingerprint(), assignment,
                    assignedSources, expectedVersions);
            resultTopicIds.add(targetTopicId);
            assignedSources.stream().map(AiTopicManualReviewResponse.SourceOption::assignedTopicId)
                    .filter(Objects::nonNull).filter(sourceTopicId -> !sourceTopicId.equals(targetTopicId))
                    .forEach(sourceTopicsToRecompute::add);
        }
        for (UUID sourceTopicId : sourceTopicsToRecompute) {
            if (itemMapper.countByTopic(sourceTopicId) == 0) {
                if (topicMapper.archiveIfEmptyAfterSplit(sourceTopicId) != 1) {
                    throw new AiTopicException("TOPIC_REVIEW_SOURCE_CONFLICT", false);
                }
            } else {
                recomputeAfterSourceSplit(sourceTopicId);
            }
        }
        String topicIds = requiredJson(resultTopicIds);
        if (reviewMapper.markApplied(previewId, topicIds, idempotencyKey) == 0) throw new AiTopicException("TOPIC_REVIEW_CONFLICT", false);
        return new AiTopicManualReviewResponse.ApplyResponse(previewId, List.copyOf(resultTopicIds), true);
    }

    private AiTopicManualReviewResponse.ApplyResponse appliedResponse(AiTopicReviewMapper.PreviewRow row) {
        try {
            List<UUID> ids = objectMapper.readValue(row.resultTopicIds(), objectMapper.getTypeFactory().constructCollectionType(List.class, UUID.class));
            return new AiTopicManualReviewResponse.ApplyResponse(row.id(), ids, true);
        } catch (Exception e) { throw new AiTopicException("TOPIC_REVIEW_RESULT_INVALID", false, e); }
    }

    private AiTopicManualReviewResponse.SourceOption sourceOption(AiTopicReviewMapper.SourceRow row) {
        return new AiTopicManualReviewResponse.SourceOption(row.id(), row.contactIdentityId(),
                SourceType.valueOf(row.sourceType()), row.channelType(), row.occurredAt(), row.direction(),
                row.subject(), row.text(), row.selectable(), row.excludedReason(), row.assignedTopicId(),
                row.assignedTopicTitle());
    }

    static String manualReviewFingerprint(List<AiTopicManualReviewResponse.SourceOption> sources) {
        String canonical = sources.stream().sorted(Comparator.comparing(AiTopicManualReviewResponse.SourceOption::id))
                .map(s -> s.id() + "|" + s.contactIdentityId() + "|" + s.sourceType() + "|" + s.occurredAt()
                        + "|" + s.assignedTopicId())
                .collect(java.util.stream.Collectors.joining("\n"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64); for (byte b : digest) out.append(String.format("%02x", b)); return out.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String fusionFingerprint(List<AiTopicEntity> topics) {
        String canonical = topics.stream().sorted(Comparator.comparing(AiTopicEntity::getId))
                .map(topic -> topic.getId() + "|" + (topic.getVersion() == null ? 1L : topic.getVersion()))
                .collect(java.util.stream.Collectors.joining("\n"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64); for (byte b : digest) out.append(String.format("%02x", b)); return out.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private List<TopicContext> readyTopicContexts(UUID contactId) {
        return topicMapper.listReadyByOwner("CONTACT", contactId).stream().map(topic -> new TopicContext(
                topic.getId(), topic.getTitle(), topic.getConfirmedSummary() == null ? topic.getAiSummary() : topic.getConfirmedSummary(),
                topic.getFirstOccurredAt(), topic.getLastOccurredAt(), itemMapper.listByTopic(topic.getId()).stream()
                .map(item -> item.getMessageId() != null ? item.getMessageId()
                        : item.getCallRecordId() != null ? item.getCallRecordId() : item.getWecomMessageSummaryJobId())
                .filter(Objects::nonNull).toList())).toList();
    }

    private AiTopicModels.SourceItem manualReviewSource(AiTopicManualReviewResponse.SourceOption source) {
        return new AiTopicModels.SourceItem(source.id(), source.sourceType(), source.channelType(), source.occurredAt(),
                source.direction() == null ? "" : source.direction(), source.subject() == null ? "" : source.subject(),
                source.text() == null ? "" : source.text());
    }

    private Map<UUID, Long> parseExpectedVersions(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructMapType(Map.class, UUID.class, Long.class));
        } catch (Exception error) {
            throw new AiTopicException("TOPIC_REVIEW_SNAPSHOT_INVALID", false, error);
        }
    }

    private List<AiTopicManualReviewResponse.Assignment> parseAssignments(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(
                    List.class, AiTopicManualReviewResponse.Assignment.class));
        } catch (Exception error) {
            throw new AiTopicException("TOPIC_REVIEW_SNAPSHOT_INVALID", false, error);
        }
    }

    private void validateManualAssignments(List<AiTopicManualReviewResponse.Assignment> assignments,
                                           List<AiTopicManualReviewResponse.SourceOption> sources,
                                           Map<UUID, Long> expectedVersions) {
        if (assignments == null || assignments.isEmpty() || assignments.size() > 20) {
            throw new AiTopicException("TOPIC_REVIEW_ASSIGNMENT_INVALID", false);
        }
        Set<UUID> allowedSourceIds = sources.stream().map(AiTopicManualReviewResponse.SourceOption::id)
                .collect(java.util.stream.Collectors.toSet());
        Set<UUID> assignedSourceIds = new HashSet<>();
        Set<String> topicKeys = new HashSet<>();
        for (AiTopicManualReviewResponse.Assignment assignment : assignments) {
            if (assignment == null || assignment.topicKey() == null || assignment.topicKey().isBlank()
                    || !topicKeys.add(assignment.topicKey()) || assignment.title() == null || assignment.title().isBlank()
                    || assignment.title().codePointCount(0, assignment.title().length()) > 200
                    || assignment.summary() == null || assignment.summary().isBlank()
                    || assignment.summary().codePointCount(0, assignment.summary().length()) > 4000
                    || assignment.sourceIds() == null || assignment.sourceIds().isEmpty()) {
                throw new AiTopicException("TOPIC_REVIEW_ASSIGNMENT_INVALID", false);
            }
            try {
                UUID topicId = UUID.fromString(assignment.topicKey());
                if (!expectedVersions.containsKey(topicId)) throw new AiTopicException("TOPIC_REVIEW_TOPIC_CONFLICT", false);
            } catch (IllegalArgumentException ignored) {
                // Opaque keys represent new topics.
            }
            for (UUID sourceId : assignment.sourceIds()) {
                if (sourceId == null || !allowedSourceIds.contains(sourceId) || !assignedSourceIds.add(sourceId)) {
                    throw new AiTopicException("TOPIC_REVIEW_ASSIGNMENT_INVALID", false);
                }
            }
        }
        if (!assignedSourceIds.equals(allowedSourceIds)) throw new AiTopicException("TOPIC_REVIEW_ASSIGNMENT_INVALID", false);
    }

    private UUID applyManualAssignment(UUID userId, UUID contactId, String fingerprint,
                                       AiTopicManualReviewResponse.Assignment assignment,
                                       List<AiTopicManualReviewResponse.SourceOption> sources,
                                       Map<UUID, Long> expectedVersions) {
        UUID existingTopicId = null;
        try { existingTopicId = UUID.fromString(assignment.topicKey()); }
        catch (IllegalArgumentException ignored) { }
        if (existingTopicId == null) {
            AiTopicEntity created = new AiTopicEntity();
            created.setId(UUID.randomUUID()); created.setOwnerType("CONTACT"); created.setOwnerId(contactId);
            created.setContactId(contactId); created.setTitle(assignment.title()); created.setAiSummary(assignment.summary());
            created.setStatus("READY"); created.setFirstOccurredAt(minOccurredAt(sources, null));
            created.setLastOccurredAt(maxOccurredAt(sources, null)); created.setInputFingerprint(fingerprint); created.setVersion(1L);
            topicMapper.insert(created);
            applyManualSources(created.getId(), contactId, sources);
            versionMapper.insertVersion(created.getId(), 1L, "MANUAL_REVIEW_APPLIED", created.getTitle(),
                    created.getAiSummary(), "[]", userId);
            return created.getId();
        }
        AiTopicEntity existing = topicMapper.selectById(existingTopicId);
        Long expectedVersion = expectedVersions.get(existingTopicId);
        if (existing == null || expectedVersion == null || !expectedVersion.equals(existing.getVersion())
                || !"CONTACT".equals(existing.getOwnerType()) || !contactId.equals(existing.getOwnerId())
                || !"READY".equals(existing.getStatus())) {
            throw new AiTopicException("TOPIC_REVIEW_TOPIC_CONFLICT", false);
        }
        applyManualSources(existingTopicId, contactId, sources);
        Instant first = minOccurredAt(sources, existing.getFirstOccurredAt());
        Instant last = maxOccurredAt(sources, existing.getLastOccurredAt());
        if (topicMapper.updateAiGenerated(existingTopicId, assignment.title(), assignment.summary(), first, last,
                fingerprint, expectedVersion) == 0) {
            throw new AiTopicException("TOPIC_VERSION_CONFLICT", false);
        }
        versionMapper.insertVersion(existingTopicId, expectedVersion + 1, "MANUAL_REVIEW_APPLIED",
                assignment.title(), assignment.summary(), "[]", userId);
        return existingTopicId;
    }

    private void applyManualSources(UUID targetTopicId, UUID contactId,
                                    List<AiTopicManualReviewResponse.SourceOption> sources) {
        for (AiTopicManualReviewResponse.SourceOption source : sources) {
            if (targetTopicId.equals(source.assignedTopicId())) {
                continue;
            }
            if (source.assignedTopicId() != null) {
                int moved = itemMapper.moveCurrentContactSourceToTopic(targetTopicId, contactId,
                        source.sourceType().name(), source.id());
                if (moved != 1) throw new AiTopicException("TOPIC_REVIEW_SOURCE_CONFLICT", false);
                continue;
            }
            int inserted = itemMapper.insertIfAbsent(targetTopicId,
                    source.sourceType() == SourceType.MESSAGE ? source.id() : null,
                    source.sourceType() == SourceType.CALL_RECORD ? source.id() : null,
                    source.sourceType() == SourceType.WECOM_SUMMARY ? source.id() : null,
                    source.occurredAt(), source.channelType());
            if (inserted != 1) throw new AiTopicException("TOPIC_REVIEW_SOURCE_CONFLICT", false);
        }
    }

    private Instant minOccurredAt(List<AiTopicManualReviewResponse.SourceOption> sources, Instant fallback) {
        return sources.stream().map(AiTopicManualReviewResponse.SourceOption::occurredAt).filter(Objects::nonNull)
                .min(Instant::compareTo).map(value -> fallback == null || value.isBefore(fallback) ? value : fallback)
                .orElse(fallback == null ? Instant.now() : fallback);
    }

    private Instant maxOccurredAt(List<AiTopicManualReviewResponse.SourceOption> sources, Instant fallback) {
        return sources.stream().map(AiTopicManualReviewResponse.SourceOption::occurredAt).filter(Objects::nonNull)
                .max(Instant::compareTo).map(value -> fallback == null || value.isAfter(fallback) ? value : fallback)
                .orElse(fallback == null ? Instant.now() : fallback);
    }

    private String requiredJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception error) { throw new AiTopicException("TOPIC_REVIEW_SNAPSHOT_INVALID", false, error); }
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
        AiTopicModels.TopicReviewOrigin reviewOrigin = topic.getReviewOrigin() == null
                ? null : AiTopicModels.TopicReviewOrigin.valueOf(topic.getReviewOrigin());
        return new TopicProjection(topic.getId(), topic.getTitle(), topic.getConfirmedSummary() == null ? topic.getAiSummary() : topic.getConfirmedSummary(), topic.getConfirmedSummary() == null ? "AI" : "EMPLOYEE", topic.getFirstOccurredAt(), topic.getLastOccurredAt(), channels, sources.size(), sources, topic.getVersion(), topic.getContactId(), topic.getContactDisplayName(), topic.getContactRemark(), topic.getContactChannelType(), topic.getContactChannelNickname(), ownerType, topic.getOwnerId(), ownerLabel, referencedGroup, reviewOrigin, topic.getReviewSourceTopicTitle());
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
