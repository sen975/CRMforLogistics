package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.entity.AiTopicOperationJobEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicGenerationJobMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicItemMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicOperationJobMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicInboxRequestMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicVersionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicOperationKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTopicStoreApprovalServiceTest {
    @Test
    void personalStoreChangesReadyTopicToStoredInTheAsyncWorker() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicVersionMapper versions = mock(AiTopicVersionMapper.class);
        AiTopicEntity topic = new AiTopicEntity();
        topic.setId(topicId);
        topic.setContactId(contactId);
        topic.setOwnerType("CONTACT");
        topic.setOwnerId(contactId);
        topic.setStatus("READY");
        topic.setVersion(3L);
        topic.setTitle("报价跟进");
        topic.setAiSummary("等待确认");
        when(topics.selectById(topicId)).thenReturn(topic);
        when(topics.transitionStatus(topicId, "READY", "STORED")).thenReturn(1);

        AiTopicOperationJobEntity job = new AiTopicOperationJobEntity();
        job.setCreatedByUserId(userId);
        job.setOperationKind(TopicOperationKind.STORE.name());
        job.setRequestPayload("{\"topicId\":\"" + topicId + "\"}");

        service(topics, versions).applyOperation(job);

        verify(topics).transitionStatus(topicId, "READY", "STORED");
        verify(versions).insertVersion(eq(topicId), eq(4L), eq("STORED"), eq("报价跟进"),
                eq("等待确认"), eq("[]"), eq(userId));
    }

    @Test
    void groupStoreCreatesOnePendingApprovalInsteadOfAStoreJob() {
        UUID userId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicEntity topic = new AiTopicEntity();
        topic.setId(topicId);
        topic.setOwnerType("WECOM_GROUP");
        topic.setOwnerId(groupId);
        topic.setStatus("READY");
        when(topics.selectById(topicId)).thenReturn(topic);
        when(topics.isAdmin(userId)).thenReturn(false);
        when(topics.canAccessGroupTopic(topicId, userId, false)).thenReturn(true);

        AiTopicInboxRequestMapper inbox = mock(AiTopicInboxRequestMapper.class);
        com.crmforlogistics.messagecenter.entity.AiTopicInboxRequestEntity request = new com.crmforlogistics.messagecenter.entity.AiTopicInboxRequestEntity();
        request.setId(UUID.randomUUID());
        request.setCreatedAt(Instant.now());
        when(inbox.findPendingByTopicId(topicId)).thenReturn(request);
        AiTopicService service = service(topics, mock(AiTopicVersionMapper.class), inbox);
        service.submitStore(userId, topicId, "group-store-1");

        // Approval is persisted, and the topic remains visible until an admin approves it.
        verify(inbox).insertPendingIfAbsent(topicId, userId);
    }

    @Test
    void contactTimelineReturnsAReferencedGroupTopicWithoutCopyingItsSources() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicItemMapper items = mock(AiTopicItemMapper.class);
        AiTopicInputService input = mock(AiTopicInputService.class);
        AiTopicGenerationJobMapper jobs = mock(AiTopicGenerationJobMapper.class);
        AiTopicEntity group = new AiTopicEntity();
        group.setId(topicId);
        group.setOwnerType("WECOM_GROUP");
        group.setOwnerId(groupId);
        group.setOwnerLabel("华东项目群");
        group.setStatus("READY");
        group.setTitle("交付排期");
        group.setAiSummary("客户确认周五交付");
        group.setFirstOccurredAt(Instant.parse("2026-09-01T01:00:00Z"));
        group.setLastOccurredAt(Instant.parse("2026-09-01T02:00:00Z"));
        group.setVersion(1L);
        when(input.collect(contactId, userId, java.util.Optional.empty()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(), "a".repeat(64), false));
        when(topics.listReady(contactId)).thenReturn(List.of());
        when(topics.listReadyReferencedGroupTopics(contactId)).thenReturn(List.of(group));
        when(jobs.findByFingerprint(contactId, "a".repeat(64))).thenReturn(null);
        when(items.listByTopic(topicId)).thenReturn(List.of());

        AiTopicService service = new AiTopicService(
                mock(com.crmforlogistics.messagecenter.service.contact.ContactService.class), input,
                topics, items, jobs, mock(AiTopicVersionMapper.class),
                new AiTopicConfigHolder(new AiTopicConfig("", "", "model", 30, 200, 262144,
                        .65, 1, 3, 120, 30)), mock(ContactIdentityMapper.class));

        var response = service.getTopics(userId, contactId);

        org.assertj.core.api.Assertions.assertThat(response.topics()).singleElement()
                .satisfies(topic -> {
                    org.assertj.core.api.Assertions.assertThat(topic.isReferencedGroupTopic()).isTrue();
                    org.assertj.core.api.Assertions.assertThat(topic.ownerLabel()).isEqualTo("华东项目群");
                    org.assertj.core.api.Assertions.assertThat(topic.sourceItems()).isEmpty();
                });
    }

    @Test
    void approvalQueuesTheGroupStoreOnlyAfterAnAdminApprovesTheRequest() {
        UUID adminId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicInboxRequestMapper inbox = mock(AiTopicInboxRequestMapper.class);
        AiTopicOperationJobMapper operations = mock(AiTopicOperationJobMapper.class);
        AiTopicEntity topic = new AiTopicEntity();
        topic.setId(topicId);
        topic.setOwnerType("WECOM_GROUP");
        topic.setOwnerId(groupId);
        topic.setStatus("READY");
        var pending = new com.crmforlogistics.messagecenter.entity.AiTopicInboxRequestEntity();
        pending.setId(requestId);
        pending.setTopicId(topicId);
        pending.setStatus("PENDING");
        pending.setCreatedAt(Instant.now());
        var approved = new com.crmforlogistics.messagecenter.entity.AiTopicInboxRequestEntity();
        approved.setId(requestId);
        approved.setTopicId(topicId);
        approved.setStatus("APPROVED");
        approved.setReviewedByUserId(adminId);
        approved.setCreatedAt(pending.getCreatedAt());
        AiTopicOperationJobEntity job = new AiTopicOperationJobEntity();
        job.setId(UUID.randomUUID());
        job.setOperationKind(TopicOperationKind.STORE.name());
        job.setStatus("PENDING");
        job.setCreatedAt(Instant.now());
        when(topics.isAdmin(adminId)).thenReturn(true);
        when(topics.selectById(topicId)).thenReturn(topic);
        when(inbox.selectById(requestId)).thenReturn(pending, approved);
        when(operations.findOrCreate(eq(null), eq("WECOM_GROUP"), eq(groupId), eq(groupId), eq(adminId),
                eq("STORE"), any(), eq("{}"), eq("approve-1"), any())).thenReturn(job);

        AiTopicService service = new AiTopicService(
                mock(com.crmforlogistics.messagecenter.service.contact.ContactService.class), mock(AiTopicInputService.class),
                topics, mock(AiTopicItemMapper.class), mock(AiTopicGenerationJobMapper.class), mock(AiTopicVersionMapper.class),
                new AiTopicConfigHolder(new AiTopicConfig("", "", "model", 30, 200, 262144,
                        .65, 1, 3, 120, 30)), mock(ContactIdentityMapper.class), operations, inbox, null, null, null);

        service.approveStore(adminId, requestId, "approve-1");

        verify(inbox).approvePending(requestId, adminId);
        verify(operations).findOrCreate(eq(null), eq("WECOM_GROUP"), eq(groupId), eq(groupId), eq(adminId),
                eq("STORE"), org.mockito.ArgumentMatchers.contains(requestId.toString()), eq("{}"), eq("approve-1"), any());
    }

    @Test
    void rejectionQueuesTheGroupStoreRequestInsteadOfCompletingSynchronously() {
        UUID adminId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicInboxRequestMapper inbox = mock(AiTopicInboxRequestMapper.class);
        AiTopicOperationJobMapper operations = mock(AiTopicOperationJobMapper.class);
        var request = new com.crmforlogistics.messagecenter.entity.AiTopicInboxRequestEntity();
        request.setId(requestId);
        request.setTopicId(topicId);
        request.setStatus("PENDING");
        request.setCreatedAt(Instant.now());
        var topic = new AiTopicEntity();
        topic.setId(topicId);
        topic.setOwnerType("WECOM_GROUP");
        topic.setOwnerId(groupId);
        topic.setStatus("READY");
        var job = new AiTopicOperationJobEntity();
        job.setId(UUID.randomUUID());
        job.setOperationKind("REJECT_STORE");
        job.setStatus("PENDING");
        job.setCreatedAt(Instant.now());
        when(topics.isAdmin(adminId)).thenReturn(true);
        when(inbox.selectById(requestId)).thenReturn(request);
        when(topics.selectById(topicId)).thenReturn(topic);
        when(operations.findOrCreate(eq(null), eq("WECOM_GROUP"), eq(groupId), eq(groupId), eq(adminId),
                eq("REJECT_STORE"), any(), eq("{}"), eq("reject-1"), any())).thenReturn(job);

        AiTopicService service = new AiTopicService(
                mock(com.crmforlogistics.messagecenter.service.contact.ContactService.class), mock(AiTopicInputService.class),
                topics, mock(AiTopicItemMapper.class), mock(AiTopicGenerationJobMapper.class), mock(AiTopicVersionMapper.class),
                new AiTopicConfigHolder(new AiTopicConfig("", "", "model", 30, 200, 262144,
                        .65, 1, 3, 120, 30)), mock(ContactIdentityMapper.class), operations, inbox, null, null, null);

        var result = service.rejectStore(adminId, requestId, "无需入库", "reject-1");

        org.assertj.core.api.Assertions.assertThat(result.status()).isEqualTo(AiTopicModels.TopicOperationStatus.PENDING);
        verify(inbox, never()).rejectPending(any(), any(), any());
        verify(operations).findOrCreate(eq(null), eq("WECOM_GROUP"), eq(groupId), eq(groupId), eq(adminId),
                eq("REJECT_STORE"), org.mockito.ArgumentMatchers.contains(requestId.toString()), eq("{}"), eq("reject-1"), any());
    }

    @Test
    void repositoryRetainsTheGroupOwnerForStoredTopics() {
        UUID userId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicEntity topic = new AiTopicEntity();
        topic.setId(topicId);
        topic.setOwnerType("WECOM_GROUP");
        topic.setOwnerId(UUID.randomUUID());
        topic.setOwnerLabel("华东项目群");
        topic.setStatus("STORED");
        topic.setTitle("交付排期");
        topic.setAiSummary("已入库");
        topic.setFirstOccurredAt(Instant.parse("2026-09-01T01:00:00Z"));
        topic.setLastOccurredAt(Instant.parse("2026-09-01T02:00:00Z"));
        topic.setVersion(2L);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<AiTopicEntity> result =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        result.setTotal(1);
        result.setRecords(List.of(topic));
        when(topics.isAdmin(userId)).thenReturn(true);
        when(topics.listStoredForUser(any(), eq(userId), eq(""), org.mockito.ArgumentMatchers.isNull(), eq(true))).thenReturn(result);

        AiTopicService service = new AiTopicService(
                mock(com.crmforlogistics.messagecenter.service.contact.ContactService.class), mock(AiTopicInputService.class),
                topics, mock(AiTopicItemMapper.class), mock(AiTopicGenerationJobMapper.class), mock(AiTopicVersionMapper.class),
                new AiTopicConfigHolder(new AiTopicConfig("", "", "model", 30, 200, 262144,
                        .65, 1, 3, 120, 30)), mock(ContactIdentityMapper.class));

        var page = service.listStored(userId, "", 1, 20);

        org.assertj.core.api.Assertions.assertThat(page.getRecords()).singleElement()
                .satisfies(value -> org.assertj.core.api.Assertions.assertThat(value.ownerLabel()).isEqualTo("华东项目群"));
    }

    @Test
    void storedTopicCannotCreateAnotherStoreRequest() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicEntity topic = new AiTopicEntity();
        topic.setId(topicId);
        topic.setContactId(contactId);
        topic.setOwnerType("CONTACT");
        topic.setOwnerId(contactId);
        topic.setStatus("STORED");
        when(topics.selectById(topicId)).thenReturn(topic);

        AiTopicService service = service(topics, mock(AiTopicVersionMapper.class));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.submitStore(userId, topicId, "duplicate-store"))
                .isInstanceOf(AiTopicException.class)
                .extracting(error -> ((AiTopicException) error).code())
                .isEqualTo("TOPIC_STORE_NOT_READY");
        verify(topics, never()).transitionStatus(any(), any(), any());
    }

    private static AiTopicService service(AiTopicMapper topics, AiTopicVersionMapper versions) {
        return service(topics, versions, null);
    }

    private static AiTopicService service(AiTopicMapper topics, AiTopicVersionMapper versions,
                                          AiTopicInboxRequestMapper inbox) {
        return new AiTopicService(
                mock(com.crmforlogistics.messagecenter.service.contact.ContactService.class),
                mock(AiTopicInputService.class), topics, mock(AiTopicItemMapper.class),
                mock(AiTopicGenerationJobMapper.class), versions,
                new AiTopicConfigHolder(new AiTopicConfig("", "", "model", 30, 200, 262144,
                        .65, 1, 3, 120, 30)),
                mock(ContactIdentityMapper.class), mock(AiTopicOperationJobMapper.class), inbox, null, null, null);
    }
}
