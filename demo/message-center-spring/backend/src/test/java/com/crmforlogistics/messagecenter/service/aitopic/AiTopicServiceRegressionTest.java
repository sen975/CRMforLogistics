package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.entity.AiTopicGenerationJobEntity;
import com.crmforlogistics.messagecenter.entity.AiTopicItemEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicGenerationJobMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicItemMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicVersionMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicGenerationAttemptMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicReviewMapper;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.GenerationStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;

class AiTopicServiceRegressionTest {
    private final UUID userId = UUID.randomUUID();
    private final UUID contactId = UUID.randomUUID();
    private final ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
    private final AiTopicMapper topics = mock(AiTopicMapper.class);
    private final AiTopicItemMapper items = mock(AiTopicItemMapper.class);
    private final AiTopicGenerationJobMapper jobs = mock(AiTopicGenerationJobMapper.class);
    private final AiTopicVersionMapper versions = mock(AiTopicVersionMapper.class);

    private AiTopicService service(AiTopicInputService input) {
        return new AiTopicService(mock(com.crmforlogistics.messagecenter.service.contact.ContactService.class), input,
                topics, items, jobs, versions,
                new AiTopicConfigHolder(new AiTopicConfig("", "", "model", 30, 200, 262144, .65, 1, 3, 120, 30)), identities);
    }

    @Test
    void readingTimelineNeverEnqueuesIncrementalJob() {
        AiTopicInputService input = mock(AiTopicInputService.class);
        String currentFingerprint = "b".repeat(64);
        AiTopicEntity topic = topic("old", Instant.parse("2026-08-01T00:00:00Z"));
        when(input.collect(contactId, userId, java.util.Optional.empty()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(source()), currentFingerprint, false));
        when(topics.listReady(contactId)).thenReturn(List.of(topic));
        when(jobs.findByFingerprint(contactId, currentFingerprint)).thenReturn(null);
        when(items.listByTopic(topic.getId())).thenReturn(List.of());

        AiTopicModels.TopicTimelineResponse response = service(input).getTopics(userId, contactId);

        assertThat(response.generation().status()).isEqualTo(GenerationStatus.READY);
        verify(jobs, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }

    @Test
    void preservesFailedInitialJobInsteadOfCreatingAnotherJobOnEveryRead() {
        AiTopicInputService input = mock(AiTopicInputService.class);
        String fingerprint = "c".repeat(64);
        AiTopicGenerationJobEntity failed = job(fingerprint, "FAILED");
        failed.setLastErrorCode("AI_RESPONSE_INVALID");
        when(input.collect(contactId, userId, java.util.Optional.empty()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(source()), fingerprint, false));
        when(topics.listReady(contactId)).thenReturn(List.of());
        when(jobs.findByFingerprint(contactId, fingerprint)).thenReturn(failed);

        AiTopicModels.TopicTimelineResponse response = service(input).getTopics(userId, contactId);

        assertThat(response.generation().status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(response.generation().errorCode()).isEqualTo("AI_RESPONSE_INVALID");
        verify(jobs, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }

    @Test
    void keepsExistingTopicsWhenAllNonWeComSourcesAreAlreadyAssigned() {
        AiTopicInputService input = mock(AiTopicInputService.class);
        AiTopicEntity existing = topic("混合沟通", Instant.parse("2026-08-01T00:00:00Z"));
        existing.setContactId(contactId);
        existing.setInputFingerprint("a".repeat(64));
        when(input.collect(contactId, userId, java.util.Optional.empty()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(), "b".repeat(64), false));
        when(topics.listReady(contactId)).thenReturn(List.of(existing));
        when(jobs.findByFingerprint(contactId, "b".repeat(64))).thenReturn(null);
        when(items.listByTopic(existing.getId())).thenReturn(List.of(item(existing.getId())));
        when(identities.findByContactId(contactId)).thenReturn(List.of(identity("wecom"), identity("email")));

        AiTopicModels.TopicTimelineResponse response = service(input).getTopics(userId, contactId);

        assertThat(response.weComUnsupported()).isFalse();
        assertThat(response.topics()).extracting(AiTopicModels.TopicProjection::title)
                .containsExactly("混合沟通");
        assertThat(response.generation().status()).isEqualTo(GenerationStatus.READY);
    }

    @Test
    void doesNotMarkMixedContactUnsupportedWhenThereAreNoNewSources() {
        AiTopicInputService input = mock(AiTopicInputService.class);
        when(input.collect(contactId, userId, java.util.Optional.empty()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(), "c".repeat(64), false));
        when(topics.listReady(contactId)).thenReturn(List.of());
        when(jobs.findByFingerprint(contactId, "c".repeat(64))).thenReturn(null);
        when(identities.findByContactId(contactId)).thenReturn(List.of(identity("wecom"), identity("email")));

        AiTopicModels.TopicTimelineResponse response = service(input).getTopics(userId, contactId);

        assertThat(response.weComUnsupported()).isFalse();
        assertThat(response.generation().status()).isEqualTo(GenerationStatus.NOT_STARTED);
    }

    @Test
    void doesNotMarkWeComOnlyContactUnsupportedBeforeItsFirstSummary() {
        AiTopicInputService input = mock(AiTopicInputService.class);
        when(input.collect(contactId, userId, java.util.Optional.empty()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(), "w".repeat(64), false));
        when(topics.listReady(contactId)).thenReturn(List.of());
        when(jobs.findByFingerprint(contactId, "w".repeat(64))).thenReturn(null);
        when(identities.findByContactId(contactId)).thenReturn(List.of(identity("wecom")));

        AiTopicModels.TopicTimelineResponse response = service(input).getTopics(userId, contactId);

        assertThat(response.weComUnsupported()).isFalse();
        assertThat(response.generation().status()).isEqualTo(GenerationStatus.NOT_STARTED);
        assertThat(response.generation().errorCode()).isNull();
    }

    @Test
    void readsReadyGroupTopicsWithoutEnqueuingWork() {
        AiTopicInputService input = mock(AiTopicInputService.class);
        UUID groupId = UUID.randomUUID();
        AiTopicEntity groupTopic = topic("群报价讨论", Instant.parse("2026-08-03T00:00:00Z"));
        groupTopic.setOwnerType("WECOM_GROUP");
        groupTopic.setOwnerId(groupId);
        when(topics.isAdmin(userId)).thenReturn(false);
        when(topics.canAccessGroupOwner(groupId, userId)).thenReturn(true);
        when(input.collect(eq(new AiTopicOwnerService.OwnerRef("WECOM_GROUP", groupId)), eq(null), any()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(), "w".repeat(64), false));
        when(topics.listReadyGroupTopics(groupId)).thenReturn(List.of(groupTopic));
        when(items.listByTopic(groupTopic.getId())).thenReturn(List.of());

        AiTopicModels.GroupTopicTimelineResponse response = service(input).getGroupTopics(userId, groupId);

        assertThat(response.sourceConversationId()).isEqualTo(groupId);
        assertThat(response.generation().status()).isEqualTo(GenerationStatus.READY);
        assertThat(response.topics()).extracting(AiTopicModels.TopicProjection::title).containsExactly("群报价讨论");
        verify(jobs, never()).insertAutomaticIfAbsent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void retriesACompletedEmptyGroupGenerationByRequeueingItsTerminalJob() {
        AiTopicInputService input = mock(AiTopicInputService.class);
        UUID groupId = UUID.randomUUID();
        String fingerprint = "r".repeat(64);
        AiTopicGenerationJobEntity completed = job(fingerprint, "COMPLETED");
        when(topics.isAdmin(userId)).thenReturn(false);
        when(topics.canAccessGroupOwner(groupId, userId)).thenReturn(true);
        when(input.collect(eq(new AiTopicOwnerService.OwnerRef("WECOM_GROUP", groupId)), eq(null), any()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(source()), fingerprint, false));
        when(jobs.findByOwnerFingerprint("WECOM_GROUP", groupId, fingerprint)).thenReturn(completed);

        AiTopicModels.GenerationProjection response = service(input).retryGroupGeneration(userId, groupId);

        assertThat(response.status()).isEqualTo(GenerationStatus.GENERATING);
        assertThat(response.jobId()).isEqualTo(completed.getId());
        verify(jobs).requeueTerminal(eq(completed.getId()), org.mockito.ArgumentMatchers.any(Instant.class));
    }

    @Test
    void retriesACompletedEmptyContactGenerationByRequeueingItsTerminalJob() {
        AiTopicInputService input = mock(AiTopicInputService.class);
        String fingerprint = "s".repeat(64);
        AiTopicGenerationJobEntity completed = job(fingerprint, "COMPLETED");
        when(input.collect(contactId, userId, java.util.Optional.empty()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(source()), fingerprint, false));
        when(jobs.findByFingerprint(contactId, fingerprint)).thenReturn(completed);

        AiTopicModels.GenerationProjection response = service(input).retryGeneration(userId, contactId);

        assertThat(response.status()).isEqualTo(GenerationStatus.GENERATING);
        assertThat(response.jobId()).isEqualTo(completed.getId());
        verify(jobs).requeueTerminal(eq(completed.getId()), org.mockito.ArgumentMatchers.any(Instant.class));
    }

    @Test
    void storesMergeSourceTopicIdsAsJsonArray() {
        var contactService = mock(com.crmforlogistics.messagecenter.service.contact.ContactService.class);
        AiTopicService service = new AiTopicService(contactService, mock(AiTopicInputService.class), topics, items, jobs,
                versions, new AiTopicConfigHolder(new AiTopicConfig("", "", "model", 30, 200, 262144, .65, 1, 3, 120, 30)), identities);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        AiTopicEntity first = topic("first", Instant.parse("2026-08-01T00:00:00Z"));
        first.setId(firstId);
        first.setContactId(contactId);
        AiTopicEntity second = topic("second", Instant.parse("2026-08-02T00:00:00Z"));
        second.setId(secondId);
        second.setContactId(contactId);
        when(topics.findContactTopicByIdAndOwner(firstId, userId)).thenReturn(first);
        when(topics.findContactTopicByIdAndOwner(secondId, userId)).thenReturn(second);
        when(items.listByTopic(firstId)).thenReturn(List.of(item(firstId)));
        when(items.listByTopic(secondId)).thenReturn(List.of(item(secondId)));

        AiTopicReviewMapper reviews = mock(AiTopicReviewMapper.class);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        List<AiTopicItemEntity> firstItems = items.listByTopic(firstId);
        List<AiTopicItemEntity> secondItems = items.listByTopic(secondId);
        when(reviews.listTopicSources(any())).thenReturn(List.of(
                new AiTopicReviewMapper.SourceRow(firstItems.get(0).getMessageId(), UUID.randomUUID(), "MESSAGE", "email",
                        firstItems.get(0).getOccurredAt(), "inbound", "", "first"),
                new AiTopicReviewMapper.SourceRow(secondItems.get(0).getMessageId(), UUID.randomUUID(), "MESSAGE", "email",
                        secondItems.get(0).getOccurredAt(), "inbound", "", "second")));
        when(gateway.fuse(any())).thenReturn(new AiTopicModels.GenerationOutput(List.of(
                new AiTopicModels.TopicAssignment("fusion", "first", "summary", 1,
                        List.of(firstItems.get(0).getMessageId(), secondItems.get(0).getMessageId())))));
        service = new AiTopicService(contactService, mock(AiTopicInputService.class), topics, items, jobs,
                versions, new AiTopicConfigHolder(new AiTopicConfig("", "", "model", 30, 200, 262144, .65, 1, 3, 120, 30)),
                identities, null, null, null, null, mock(AiTopicGenerationAttemptMapper.class), reviews, gateway);

        service.mergeTopics(userId, List.of(firstId, secondId), Map.of(firstId, 1L, secondId, 1L));

        verify(versions).insertVersion(any(UUID.class), eq(1L), eq("MERGED"), eq("first"), any(), eq("[\"" + firstId + "\",\"" + secondId + "\"]"), eq(userId));
        verify(versions, times(2)).insertVersion(any(UUID.class), eq(2L), eq("MERGED_INTO"), anyString(), anyString(), anyString(), eq(userId));
    }

    @Test
    void rejectsGenerationWhenWorkerActorDoesNotMatchPersistedJobActor() {
        AiTopicInputService input = mock(AiTopicInputService.class);
        AiTopicGenerationJobEntity job = job("d".repeat(64), "PROCESSING");
        job.setContactId(contactId);
        job.setCreatedByUserId(userId);

        assertThatThrownBy(() -> service(input).generate(job, UUID.randomUUID(), mock(TopicAiGateway.class)))
                .isInstanceOf(AiTopicException.class)
                .extracting(error -> ((AiTopicException) error).code())
                .isEqualTo("AI_AUTH_CONTEXT_MISSING");
    }

    @Test
    void automaticGroupJobDoesNotRequireEmployeeActor() {
        AiTopicInputService input = mock(AiTopicInputService.class);
        UUID groupId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        AiTopicGenerationJobEntity job = job("f".repeat(64), "PROCESSING");
        job.setOwnerType("WECOM_GROUP");
        job.setOwnerId(groupId);
        job.setWecomGroupSourceConversationId(groupId);
        job.setTriggerSource("AUTO");
        job.setJobKind("INITIAL");
        when(input.collect(eq(new AiTopicOwnerService.OwnerRef("WECOM_GROUP", groupId)), eq(null), any()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(new AiTopicModels.SourceItem(sourceId,
                        AiTopicModels.SourceType.WECOM_SUMMARY, "wecom", Instant.parse("2026-08-03T00:00:00Z"),
                        "inbound", "", "官方摘要")), "f".repeat(64), false));
        when(topics.listReadyByOwner("WECOM_GROUP", groupId)).thenReturn(List.of());
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        when(gateway.generate(any())).thenReturn(new AiTopicModels.GenerationOutput(List.of(
                new AiTopicModels.TopicAssignment("new", "群沟通", "概要", 0, List.of(sourceId)))));

        service(input).generate(job, null, gateway);

        ArgumentCaptor<AiTopicEntity> created = ArgumentCaptor.forClass(AiTopicEntity.class);
        verify(topics).insert(created.capture());
        assertThat(created.getValue().getOwnerType()).isEqualTo("WECOM_GROUP");
        assertThat(created.getValue().getOwnerId()).isEqualTo(groupId);
        assertThat(created.getValue().getContactId()).isNull();
        ArgumentCaptor<AiTopicModels.GenerationInput> request = ArgumentCaptor.forClass(AiTopicModels.GenerationInput.class);
        verify(gateway).generate(request.capture());
        assertThat(request.getValue().owner()).isEqualTo(new AiTopicOwnerService.OwnerRef("WECOM_GROUP", groupId));
        assertThat(request.getValue().existingTopics()).isEmpty();
        verify(items).insertIfAbsent(any(), eq(null), eq(null), eq(sourceId), any(), eq("wecom"));
    }

    @Test
    void rejectsGatewayOutputThatDuplicatesOrOmitsBatchSourcesBeforeWritingTopics() {
        AiTopicInputService input = mock(AiTopicInputService.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(input.collect(eq(new AiTopicOwnerService.OwnerRef("CONTACT", contactId)), eq(userId), any()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(
                        new AiTopicModels.SourceItem(first, AiTopicModels.SourceType.MESSAGE, "email", Instant.now(), "inbound", "", "first"),
                        new AiTopicModels.SourceItem(second, AiTopicModels.SourceType.MESSAGE, "email", Instant.now(), "inbound", "", "second")),
                        "g".repeat(64), false));
        when(topics.listReadyByOwner("CONTACT", contactId)).thenReturn(List.of());
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        when(gateway.generate(any())).thenReturn(new AiTopicModels.GenerationOutput(List.of(
                new AiTopicModels.TopicAssignment("first", "标题一", "摘要一", 0.1, List.of(first)),
                new AiTopicModels.TopicAssignment("duplicate", "标题二", "摘要二", 0.1, List.of(first)))));
        AiTopicGenerationJobEntity job = job("g".repeat(64), "PROCESSING");
        job.setContactId(contactId); job.setOwnerType("CONTACT"); job.setOwnerId(contactId); job.setCreatedByUserId(userId);

        assertThatThrownBy(() -> service(input).generate(job, userId, gateway))
                .isInstanceOf(AiTopicException.class)
                .extracting(error -> ((AiTopicException) error).code())
                .isEqualTo("AI_RESPONSE_INVALID");

        verify(topics, never()).insert(any(AiTopicEntity.class));
        verify(items, never()).insertIfAbsent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsStoredTopicUuidInsteadOfTreatingItAsAnExistingTopic() {
        AiTopicInputService input = mock(AiTopicInputService.class);
        UUID sourceId = UUID.randomUUID();
        UUID storedId = UUID.randomUUID();
        when(input.collect(eq(new AiTopicOwnerService.OwnerRef("CONTACT", contactId)), eq(userId), any()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(new AiTopicModels.SourceItem(sourceId,
                        AiTopicModels.SourceType.MESSAGE, "email", Instant.now(), "inbound", "", "message")), "h".repeat(64), false));
        when(topics.listReadyByOwner("CONTACT", contactId)).thenReturn(List.of());
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        when(gateway.generate(any())).thenReturn(new AiTopicModels.GenerationOutput(List.of(
                new AiTopicModels.TopicAssignment(storedId.toString(), "历史主题", "不应重用", 0.9, List.of(sourceId)))));
        AiTopicGenerationJobEntity job = job("h".repeat(64), "PROCESSING");
        job.setContactId(contactId); job.setOwnerType("CONTACT"); job.setOwnerId(contactId); job.setCreatedByUserId(userId);

        assertThatThrownBy(() -> service(input).generate(job, userId, gateway))
                .isInstanceOf(AiTopicException.class)
                .extracting(error -> ((AiTopicException) error).code())
                .isEqualTo("AI_RESPONSE_INVALID");

        verify(topics, never()).selectById(storedId);
        verify(topics, never()).insert(any(AiTopicEntity.class));
    }

    @Test
    void recordsServiceSideAssignmentValidationAtTheValidationStage() {
        AiTopicInputService input = mock(AiTopicInputService.class);
        UUID sourceId = UUID.randomUUID();
        when(input.collect(eq(new AiTopicOwnerService.OwnerRef("CONTACT", contactId)), eq(userId), any()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(new AiTopicModels.SourceItem(sourceId,
                        AiTopicModels.SourceType.MESSAGE, "email", Instant.now(), "inbound", "", "message")), "i".repeat(64), false));
        when(topics.listReadyByOwner("CONTACT", contactId)).thenReturn(List.of());
        AiTopicGenerationAttemptMapper attempts = mock(AiTopicGenerationAttemptMapper.class);
        AiTopicGenerationAuditService audit = new AiTopicGenerationAuditService(attempts, new com.fasterxml.jackson.databind.ObjectMapper());
        AiTopicService audited = new AiTopicService(mock(com.crmforlogistics.messagecenter.service.contact.ContactService.class), input,
                topics, items, jobs, versions,
                new AiTopicConfigHolder(new AiTopicConfig("https://provider.example", "", "model", 30, 200, 262144, .65, 1, 3, 120, 30)), identities,
                null, null, audit, attempts);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        when(gateway.generate(any(), any())).thenReturn(new AiTopicModels.GenerationOutput(List.of(
                new AiTopicModels.TopicAssignment("new", "标题", "摘要", 0.1, List.of(UUID.randomUUID())))));
        AiTopicGenerationJobEntity job = job("i".repeat(64), "PROCESSING");
        job.setContactId(contactId); job.setOwnerType("CONTACT"); job.setOwnerId(contactId); job.setCreatedByUserId(userId);

        assertThatThrownBy(() -> audited.generate(job, userId, gateway)).isInstanceOf(AiTopicException.class);

        ArgumentCaptor<com.crmforlogistics.messagecenter.entity.AiTopicGenerationAttemptEntity> outcome = ArgumentCaptor.forClass(com.crmforlogistics.messagecenter.entity.AiTopicGenerationAttemptEntity.class);
        verify(attempts).updateOutcome(outcome.capture());
        assertThat(outcome.getValue().getStage()).isEqualTo("RESPONSE_VALIDATE");
        assertThat(outcome.getValue().getParsedResponse()).contains("new");
    }

    @Test
    void assignsUuidToNewTopicBeforeInserting() {
        AiTopicInputService input = mock(AiTopicInputService.class);
        UUID sourceId = UUID.randomUUID();
        AiTopicModels.SourceItem source = new AiTopicModels.SourceItem(sourceId, AiTopicModels.SourceType.MESSAGE, "email",
                Instant.parse("2026-08-03T00:00:00Z"), "inbound", "subject", "body");
        when(input.collect(eq(new AiTopicOwnerService.OwnerRef("CONTACT", contactId)), eq(userId), any()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(source), "e".repeat(64), false));
        when(topics.listReadyByOwner("CONTACT", contactId)).thenReturn(List.of());
        var assignment = new AiTopicModels.TopicAssignment("new-topic-1", "标题", "摘要", 0.9, List.of(sourceId));
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        when(gateway.generate(any())).thenReturn(new AiTopicModels.GenerationOutput(List.of(assignment)));
        AiTopicGenerationJobEntity job = new AiTopicGenerationJobEntity();
        job.setContactId(contactId);
        job.setOwnerType("CONTACT");
        job.setOwnerId(contactId);
        job.setCreatedByUserId(userId);
        job.setInputFingerprint("e".repeat(64));
        job.setJobKind("INITIAL");

        service(input).generate(job, userId, gateway);

        ArgumentCaptor<AiTopicEntity> created = ArgumentCaptor.forClass(AiTopicEntity.class);
        verify(topics).insert(created.capture());
        assertThat(created.getValue().getId()).isNotNull();
    }

    @Test
    void matchedReadyTopicAdoptsLatestAiTitleAndSummary() {
        AiTopicInputService input = mock(AiTopicInputService.class);
        AiTopicModels.SourceItem incoming = source();
        AiTopicEntity existing = topic("旧标题", Instant.parse("2026-08-01T00:00:00Z"));
        existing.setContactId(contactId);
        existing.setOwnerType("CONTACT");
        existing.setOwnerId(contactId);
        existing.setConfirmedSummary("旧人工概要");
        when(input.collect(eq(new AiTopicOwnerService.OwnerRef("CONTACT", contactId)), eq(userId), any()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(incoming), "n".repeat(64), false));
        when(topics.listReadyByOwner("CONTACT", contactId)).thenReturn(List.of(existing));
        when(topics.selectById(existing.getId())).thenReturn(existing);
        when(items.listByTopic(existing.getId())).thenReturn(List.of(item(existing.getId())));
        when(items.insertIfAbsent(eq(existing.getId()), eq(incoming.id()), eq(null), eq(null),
                eq(incoming.occurredAt()), eq("email"))).thenReturn(1);
        when(topics.updateAiGenerated(eq(existing.getId()), anyString(), anyString(), any(), any(),
                anyString(), eq(1L))).thenReturn(1);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        when(gateway.generate(any())).thenReturn(new AiTopicModels.GenerationOutput(List.of(
                new AiTopicModels.TopicAssignment(existing.getId().toString(), "新标题", "融合旧信息与新消息的新概要",
                        .9, List.of(incoming.id())))));
        AiTopicGenerationJobEntity job = job("n".repeat(64), "PROCESSING");
        job.setContactId(contactId);
        job.setOwnerType("CONTACT");
        job.setOwnerId(contactId);
        job.setCreatedByUserId(userId);
        job.setJobKind("INCREMENTAL");

        service(input).generate(job, userId, gateway);

        verify(topics).updateAiGenerated(eq(existing.getId()), eq("新标题"),
                eq("融合旧信息与新消息的新概要"), any(), eq(incoming.occurredAt()),
                eq("n".repeat(64)), eq(1L));
        assertThat(existing.getTitle()).isEqualTo("新标题");
        assertThat(existing.getAiSummary()).isEqualTo("融合旧信息与新消息的新概要");
        assertThat(existing.getConfirmedSummary()).isNull();
    }

    @Test
    void recomputesReadyTopicFromRemainingSourcesAfterContactSplit() {
        UUID topicId = UUID.randomUUID();
        UUID remainingSourceId = UUID.randomUUID();
        AiTopicEntity existing = topic("拆分前标题", Instant.parse("2026-08-01T00:00:00Z"));
        existing.setId(topicId);
        existing.setContactId(contactId);
        existing.setOwnerType("CONTACT");
        existing.setOwnerId(contactId);
        existing.setConfirmedSummary("拆分前人工概要");
        AiTopicReviewMapper reviews = mock(AiTopicReviewMapper.class);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        Instant remainingAt = Instant.parse("2026-08-02T00:00:00Z");
        when(topics.selectById(topicId)).thenReturn(existing);
        when(reviews.listTopicSources(List.of(topicId))).thenReturn(List.of(
                new AiTopicReviewMapper.SourceRow(remainingSourceId, UUID.randomUUID(), "MESSAGE", "email",
                        remainingAt, "inbound", "报价", "仅保留的邮件")));
        when(gateway.fuse(any())).thenReturn(new AiTopicModels.GenerationOutput(List.of(
                new AiTopicModels.TopicAssignment("recomputed", "拆分后标题", "只描述剩余来源的新概要", 1,
                        List.of(remainingSourceId)))));
        when(topics.updateAfterSplit(eq(topicId), anyString(), anyString(), any(), any(), anyString(), eq(1L)))
                .thenReturn(1);
        AiTopicService reconciler = new AiTopicService(
                mock(com.crmforlogistics.messagecenter.service.contact.ContactService.class),
                mock(AiTopicInputService.class), topics, items, jobs, versions,
                new AiTopicConfigHolder(new AiTopicConfig("", "", "model", 30, 200, 262144, .65, 1, 3, 120, 30)),
                identities, null, null, null, null, mock(AiTopicGenerationAttemptMapper.class), reviews, gateway);

        reconciler.recomputeAfterSourceSplit(topicId);

        verify(topics).updateAfterSplit(eq(topicId), eq("拆分后标题"), eq("只描述剩余来源的新概要"),
                eq(remainingAt), eq(remainingAt), anyString(), eq(1L));
        verify(versions).insertVersion(topicId, 2L, "AI_GENERATED", "拆分后标题",
                "只描述剩余来源的新概要", "[]", null);
        assertThat(existing.getTitle()).isEqualTo("拆分后标题");
        assertThat(existing.getAiSummary()).isEqualTo("只描述剩余来源的新概要");
        assertThat(existing.getConfirmedSummary()).isNull();
    }

    @Test
    void rejectsSplitRecomputeWhenAiOmitsARemainingSource() {
        UUID topicId = UUID.randomUUID();
        UUID firstSourceId = UUID.randomUUID();
        UUID secondSourceId = UUID.randomUUID();
        AiTopicEntity existing = topic("拆分前标题", Instant.parse("2026-08-01T00:00:00Z"));
        existing.setId(topicId);
        existing.setContactId(contactId);
        existing.setOwnerType("CONTACT");
        existing.setOwnerId(contactId);
        AiTopicReviewMapper reviews = mock(AiTopicReviewMapper.class);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        when(topics.selectById(topicId)).thenReturn(existing);
        when(reviews.listTopicSources(List.of(topicId))).thenReturn(List.of(
                new AiTopicReviewMapper.SourceRow(firstSourceId, UUID.randomUUID(), "MESSAGE", "email",
                        Instant.parse("2026-08-02T00:00:00Z"), "inbound", "", "第一条"),
                new AiTopicReviewMapper.SourceRow(secondSourceId, UUID.randomUUID(), "MESSAGE", "email",
                        Instant.parse("2026-08-03T00:00:00Z"), "inbound", "", "第二条")));
        when(gateway.fuse(any())).thenReturn(new AiTopicModels.GenerationOutput(List.of(
                new AiTopicModels.TopicAssignment("recomputed", "错误标题", "遗漏来源的概要", 1,
                        List.of(firstSourceId)))));
        AiTopicService reconciler = new AiTopicService(
                mock(com.crmforlogistics.messagecenter.service.contact.ContactService.class),
                mock(AiTopicInputService.class), topics, items, jobs, versions,
                new AiTopicConfigHolder(new AiTopicConfig("", "", "model", 30, 200, 262144, .65, 1, 3, 120, 30)),
                identities, null, null, null, null, mock(AiTopicGenerationAttemptMapper.class), reviews, gateway);

        assertThatThrownBy(() -> reconciler.recomputeAfterSourceSplit(topicId))
                .isInstanceOf(AiTopicException.class)
                .extracting(error -> ((AiTopicException) error).code())
                .isEqualTo("TOPIC_SPLIT_RECONCILE_RESPONSE_INVALID");

        verify(topics, never()).updateAfterSplit(any(), any(), any(), any(), any(), any(), anyLong());
    }

    private AiTopicModels.SourceItem source() {
        return new AiTopicModels.SourceItem(UUID.randomUUID(), AiTopicModels.SourceType.MESSAGE, "email",
                Instant.parse("2026-08-03T00:00:00Z"), "inbound", "subject", "body");
    }

    private static AiTopicEntity topic(String title, Instant firstAt) {
        AiTopicEntity topic = new AiTopicEntity();
        topic.setId(UUID.randomUUID());
        topic.setContactId(UUID.randomUUID());
        topic.setTitle(title);
        topic.setAiSummary("summary");
        topic.setStatus("READY");
        topic.setFirstOccurredAt(firstAt);
        topic.setLastOccurredAt(firstAt);
        topic.setInputFingerprint("a".repeat(64));
        topic.setVersion(1L);
        return topic;
    }

    private static AiTopicGenerationJobEntity job(String fingerprint, String status) {
        AiTopicGenerationJobEntity job = new AiTopicGenerationJobEntity();
        job.setId(UUID.randomUUID());
        job.setInputFingerprint(fingerprint);
        job.setStatus(status);
        job.setUpdatedAt(Instant.now());
        return job;
    }

    private static AiTopicItemEntity item(UUID topicId) {
        AiTopicItemEntity item = new AiTopicItemEntity();
        item.setTopicId(topicId);
        item.setMessageId(UUID.randomUUID());
        item.setOccurredAt(Instant.parse("2026-08-01T00:00:00Z"));
        item.setChannelType("email");
        return item;
    }

    private static ContactIdentityEntity identity(String channelType) {
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setChannelType(channelType);
        return identity;
    }
}
