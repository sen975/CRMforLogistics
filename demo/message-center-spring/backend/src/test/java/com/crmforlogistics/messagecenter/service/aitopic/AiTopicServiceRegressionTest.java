package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.entity.AiTopicGenerationJobEntity;
import com.crmforlogistics.messagecenter.entity.AiTopicItemEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicGenerationJobMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicItemMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicVersionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
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
    void enqueuesIncrementalJobWhenCurrentFingerprintDiffersFromProcessedTopic() {
        AiTopicInputService input = mock(AiTopicInputService.class);
        String currentFingerprint = "b".repeat(64);
        AiTopicEntity topic = topic("old", Instant.parse("2026-08-01T00:00:00Z"));
        AiTopicGenerationJobEntity job = job(currentFingerprint, "PENDING");
        when(input.collect(contactId, userId, java.util.Optional.empty()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(source()), currentFingerprint, false));
        when(topics.listReady(contactId)).thenReturn(List.of(topic));
        when(jobs.findByFingerprint(contactId, currentFingerprint)).thenReturn(null, job);
        when(jobs.insertIfAbsent(eq(contactId), eq(userId), eq("INCREMENTAL"), eq(currentFingerprint), any())).thenReturn(1);
        when(items.listByTopic(topic.getId())).thenReturn(List.of());

        AiTopicModels.TopicTimelineResponse response = service(input).getTopics(userId, contactId);

        assertThat(response.generation().status()).isEqualTo(GenerationStatus.GENERATING);
        verify(jobs).insertIfAbsent(eq(contactId), eq(userId), eq("INCREMENTAL"), eq(currentFingerprint), any());
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
        when(topics.selectById(firstId)).thenReturn(first);
        when(topics.selectById(secondId)).thenReturn(second);
        when(items.listByTopic(firstId)).thenReturn(List.of(item(firstId)));
        when(items.listByTopic(secondId)).thenReturn(List.of(item(secondId)));

        service.mergeTopics(userId, List.of(firstId, secondId), Map.of(firstId, 1L, secondId, 1L));

        verify(versions).insertVersion(eq(firstId), eq(2L), eq("MERGED"), eq("first"), any(), eq("[\"" + firstId + "\",\"" + secondId + "\"]"), eq(userId));
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
    void assignsUuidToNewTopicBeforeInserting() {
        AiTopicInputService input = mock(AiTopicInputService.class);
        UUID sourceId = UUID.randomUUID();
        AiTopicModels.SourceItem source = new AiTopicModels.SourceItem(sourceId, AiTopicModels.SourceType.MESSAGE, "email",
                Instant.parse("2026-08-03T00:00:00Z"), "inbound", "subject", "body");
        when(input.collect(contactId, userId, java.util.Optional.empty()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(source), "e".repeat(64), false));
        when(topics.listReady(contactId)).thenReturn(List.of());
        var assignment = new AiTopicModels.TopicAssignment("new-topic-1", "标题", "摘要", 0.9, List.of(sourceId));
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        when(gateway.generate(any())).thenReturn(new AiTopicModels.GenerationOutput(List.of(assignment)));
        AiTopicGenerationJobEntity job = new AiTopicGenerationJobEntity();
        job.setContactId(contactId);
        job.setCreatedByUserId(userId);
        job.setInputFingerprint("e".repeat(64));
        job.setJobKind("INITIAL");

        service(input).generate(job, userId, gateway);

        ArgumentCaptor<AiTopicEntity> created = ArgumentCaptor.forClass(AiTopicEntity.class);
        verify(topics).insert(created.capture());
        assertThat(created.getValue().getId()).isNotNull();
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
}
