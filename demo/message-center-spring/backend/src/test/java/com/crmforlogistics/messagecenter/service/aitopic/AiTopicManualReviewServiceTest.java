package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import com.crmforlogistics.messagecenter.dto.request.AiTopicManualReviewRequest;
import com.crmforlogistics.messagecenter.dto.response.AiTopicManualReviewResponse;
import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.entity.AiTopicItemEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.mapper.*;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiTopicManualReviewServiceTest {
    private static final Instant AT = Instant.parse("2026-09-01T02:00:00Z");

    @Test
    void sourceQueryKeepsRecordsThatAlreadyBelongToATopicForManualReconciliation() {
        assertThat(AiTopicReviewMapper.SOURCE_UNION)
                .doesNotContain("and not exists (select 1 from ai_topic_items");
    }

    @Test
    void sourceQueryIncludesWhatsAppMessagesWithoutAnAiSummaryRequirement() {
        assertThat(AiTopicReviewMapper.SOURCE_UNION)
                .contains("ca.channel_type in ('chatapp','email','whatsapp')")
                .doesNotContain("wecom_message_summary_jobs j join messages");
    }

    @Test
    void manualReviewHasAnAtomicSourceTransferForCurrentContactTopics() throws Exception {
        assertThat(AiTopicItemMapper.class.getMethod("moveCurrentContactSourceToTopic",
                UUID.class, UUID.class, String.class, UUID.class)).isNotNull();
    }

    @Test
    void previewUsesSelectedIdentityCallsAiAndPersistsAssignmentsAndVersions() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID readyTopicId = UUID.randomUUID();
        AiTopicReviewMapper reviews = mock(AiTopicReviewMapper.class);
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicItemMapper items = mock(AiTopicItemMapper.class);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        AiTopicEntity ready = topic(readyTopicId, contactId, 4L);
        when(reviews.listSources(contactId, identityId, null, null, 201)).thenReturn(List.of(
                new AiTopicReviewMapper.SourceRow(sourceId, identityId, "MESSAGE", "email", AT,
                        "outbound", "报价", "报价为 100 元")));
        when(topics.listReadyByOwner("CONTACT", contactId)).thenReturn(List.of(ready));
        when(items.listByTopic(readyTopicId)).thenReturn(List.of(item(readyTopicId)));
        when(gateway.generate(any())).thenReturn(new AiTopicModels.GenerationOutput(List.of(
                new AiTopicModels.TopicAssignment(readyTopicId.toString(), "报价跟进", "补充了最新报价", .91,
                        List.of(sourceId)))));

        AiTopicService service = service(topics, items, reviews, gateway);
        var result = service.previewManualReview(userId, contactId,
                new AiTopicManualReviewRequest(List.of(sourceId), null, null, null, identityId, null));

        assertThat(result.assignments()).singleElement().satisfies(assignment -> {
            assertThat(assignment.topicKey()).isEqualTo(readyTopicId.toString());
            assertThat(assignment.relevance()).isEqualTo(.91);
            assertThat(assignment.sourceIds()).containsExactly(sourceId);
        });
        assertThat(result.expectedVersions()).containsEntry(readyTopicId, 4L);
        verify(gateway).generate(argThat(input -> input.incremental()
                && input.owner().equals(AiTopicOwnerService.contact(contactId))
                && input.sources().size() == 1
                && input.existingTopics().size() == 1));
        verify(reviews).insertPreview(any(), eq(contactId), anyString(), contains(sourceId.toString()),
                contains("报价跟进"), contains(readyTopicId.toString()), isNull(), isNull(), eq(userId), any());
    }

    @Test
    void applyUpdatesExistingTopicAndRejectsDuplicateSourceInsertion() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID readyTopicId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();
        AiTopicReviewMapper reviews = mock(AiTopicReviewMapper.class);
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicItemMapper items = mock(AiTopicItemMapper.class);
        AiTopicEntity ready = topic(readyTopicId, contactId, 4L);
        var source = new AiTopicManualReviewResponse.SourceOption(sourceId, identityId,
                AiTopicModels.SourceType.MESSAGE, "email", AT, "outbound", "报价", "报价为 100 元", true);
        var assignment = new AiTopicManualReviewResponse.Assignment(readyTopicId.toString(), "报价跟进",
                "补充了最新报价", .91, List.of(sourceId));
        String fingerprint = AiTopicService.manualReviewFingerprint(List.of(source));
        AiTopicReviewMapper.PreviewRow preview = new AiTopicReviewMapper.PreviewRow(previewId, contactId,
                fingerprint, json(source), json(assignment), "{\"" + readyTopicId + "\":4}", null, null,
                "PENDING", "[]", userId, Instant.now().plusSeconds(300));
        when(reviews.findByIdempotency(userId, "apply-1")).thenReturn(null);
        when(reviews.findPreview(previewId)).thenReturn(preview);
        when(reviews.listSourcesByIds(eq(contactId), eq(identityId), eq(List.of(sourceId)))).thenReturn(List.of(
                new AiTopicReviewMapper.SourceRow(sourceId, identityId, "MESSAGE", "email", AT,
                        "outbound", "报价", "报价为 100 元")));
        when(topics.selectById(readyTopicId)).thenReturn(ready);
        when(items.listByTopic(readyTopicId)).thenReturn(List.of(item(readyTopicId)));
        when(items.insertIfAbsent(eq(readyTopicId), eq(sourceId), isNull(), isNull(), eq(AT), eq("email"))).thenReturn(1);
        when(topics.updateAiGenerated(eq(readyTopicId), eq("报价跟进"), eq("补充了最新报价"), any(), any(),
                eq(fingerprint), eq(4L))).thenReturn(1);
        when(reviews.markApplied(eq(previewId), anyString(), eq("apply-1"))).thenReturn(1);

        AiTopicService service = service(topics, items, reviews, mock(TopicAiGateway.class));
        var result = service.applyManualReview(userId, contactId, previewId,
                new AiTopicManualReviewRequest(null, fingerprint, null, null, identityId, null), "apply-1");

        assertThat(result.topicIds()).containsExactly(readyTopicId);
        verify(topics).updateAiGenerated(eq(readyTopicId), eq("报价跟进"), eq("补充了最新报价"), any(), any(),
                eq(fingerprint), eq(4L));
        verify(reviews).markApplied(eq(previewId), contains(readyTopicId.toString()), eq("apply-1"));
    }

    @Test
    void applyMovesAnAlreadyAssignedSourceAndRecomputesItsPreviousTopic() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID remainingSourceId = UUID.randomUUID();
        UUID previousTopicId = UUID.randomUUID();
        UUID targetTopicId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();
        AiTopicReviewMapper reviews = mock(AiTopicReviewMapper.class);
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicItemMapper items = mock(AiTopicItemMapper.class);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        AiTopicEntity previous = topic(previousTopicId, contactId, 2L);
        AiTopicEntity target = topic(targetTopicId, contactId, 4L);
        var source = new AiTopicManualReviewResponse.SourceOption(sourceId, identityId,
                AiTopicModels.SourceType.MESSAGE, "email", AT, "outbound", "报价", "新报价", true, null,
                previousTopicId, "旧报价 Topic");
        var assignment = new AiTopicManualReviewResponse.Assignment(targetTopicId.toString(), "报价跟进",
                "补充了最新报价", .91, List.of(sourceId));
        String fingerprint = AiTopicService.manualReviewFingerprint(List.of(source));
        AiTopicReviewMapper.PreviewRow preview = new AiTopicReviewMapper.PreviewRow(previewId, contactId,
                fingerprint, json(source), json(assignment), "{\"" + targetTopicId + "\":4,\""
                + previousTopicId + "\":2}", null, null, "PENDING", "[]", userId, Instant.now().plusSeconds(300));
        when(reviews.findByIdempotency(userId, "apply-2")).thenReturn(null);
        when(reviews.findPreview(previewId)).thenReturn(preview);
        when(reviews.listSourcesByIds(contactId, identityId, List.of(sourceId))).thenReturn(List.of(
                new AiTopicReviewMapper.SourceRow(sourceId, identityId, "MESSAGE", "email", AT,
                        "outbound", "报价", "新报价", true, null, previousTopicId, "旧报价 Topic")));
        when(topics.selectById(targetTopicId)).thenReturn(target);
        when(topics.selectById(previousTopicId)).thenReturn(previous);
        when(items.moveCurrentContactSourceToTopic(targetTopicId, contactId, "MESSAGE", sourceId)).thenReturn(1);
        when(topics.updateAiGenerated(targetTopicId, "报价跟进", "补充了最新报价",
                AT.minusSeconds(3600), AT, fingerprint, 4L)).thenReturn(1);
        when(items.countByTopic(previousTopicId)).thenReturn(1);
        when(reviews.listTopicSources(List.of(previousTopicId))).thenReturn(List.of(
                new AiTopicReviewMapper.SourceRow(remainingSourceId, identityId, "MESSAGE", "email",
                        AT.minusSeconds(300), "inbound", "旧报价", "旧报价内容")));
        when(gateway.fuse(any())).thenReturn(new AiTopicModels.GenerationOutput(List.of(
                new AiTopicModels.TopicAssignment("fusion", "旧报价", "只保留旧报价内容", 1d,
                        List.of(remainingSourceId)))));
        when(topics.updateAfterSplit(eq(previousTopicId), eq("旧报价"), eq("只保留旧报价内容"),
                eq(AT.minusSeconds(300)), eq(AT.minusSeconds(300)), anyString(), eq(2L))).thenReturn(1);
        when(reviews.markApplied(eq(previewId), anyString(), eq("apply-2"))).thenReturn(1);

        AiTopicService service = service(topics, items, reviews, gateway);
        var result = service.applyManualReview(userId, contactId, previewId,
                new AiTopicManualReviewRequest(null, fingerprint, null, null, identityId, null), "apply-2");

        assertThat(result.topicIds()).containsExactly(targetTopicId);
        verify(items).moveCurrentContactSourceToTopic(targetTopicId, contactId, "MESSAGE", sourceId);
        verify(gateway).fuse(any());
        verify(topics).updateAfterSplit(eq(previousTopicId), eq("旧报价"), eq("只保留旧报价内容"),
                any(), any(), anyString(), eq(2L));
    }

    @Test
    void listingEmailSourcesDoesNotReportIncompleteWeComSummary() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID emailIdentityId = UUID.randomUUID();
        UUID wecomIdentityId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        AiTopicReviewMapper reviews = mock(AiTopicReviewMapper.class);
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicItemMapper items = mock(AiTopicItemMapper.class);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);

        ContactIdentityEntity emailIdentity = new ContactIdentityEntity();
        emailIdentity.setId(emailIdentityId);
        emailIdentity.setContactId(contactId);
        emailIdentity.setChannelType("email");
        ContactIdentityEntity wecomIdentity = new ContactIdentityEntity();
        wecomIdentity.setId(wecomIdentityId);
        wecomIdentity.setContactId(contactId);
        wecomIdentity.setChannelType("wecom");

        when(identities.findByContactId(contactId)).thenReturn(List.of(emailIdentity, wecomIdentity));
        when(identities.selectById(emailIdentityId)).thenReturn(emailIdentity);
        when(reviews.listSources(contactId, emailIdentityId, null, null, 201)).thenReturn(List.of(
                new AiTopicReviewMapper.SourceRow(sourceId, emailIdentityId, "MESSAGE", "email", AT,
                        "inbound", "主题", "正文")));

        AiTopicService service = service(topics, items, reviews, gateway, identities);
        var result = service.listManualReviewSources(userId, contactId, emailIdentityId, null, null);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).channelType()).isEqualTo("email");
        assertThat(result.wecomExcludedReason()).isNull();
    }

    private static AiTopicService service(AiTopicMapper topics, AiTopicItemMapper items,
                                          AiTopicReviewMapper reviews, TopicAiGateway gateway) {
        return service(topics, items, reviews, gateway, mock(ContactIdentityMapper.class));
    }

    private static AiTopicService service(AiTopicMapper topics, AiTopicItemMapper items,
                                          AiTopicReviewMapper reviews, TopicAiGateway gateway,
                                          ContactIdentityMapper identities) {
        return new AiTopicService(mock(ContactService.class), mock(AiTopicInputService.class), topics, items,
                mock(AiTopicGenerationJobMapper.class), mock(AiTopicVersionMapper.class),
                new AiTopicConfigHolder(new AiTopicConfig("", "", "model", 30, 200, 262144, .65, 1, 3, 120, 30)),
                identities, mock(AiTopicOperationJobMapper.class),
                mock(AiTopicInboxRequestMapper.class), null, null, mock(AiTopicGenerationAttemptMapper.class),
                reviews, gateway);
    }

    private static AiTopicEntity topic(UUID id, UUID contactId, long version) {
        AiTopicEntity topic = new AiTopicEntity();
        topic.setId(id); topic.setContactId(contactId); topic.setOwnerType("CONTACT"); topic.setOwnerId(contactId);
        topic.setTitle("报价"); topic.setAiSummary("已有报价讨论"); topic.setStatus("READY");
        topic.setFirstOccurredAt(AT.minusSeconds(3600)); topic.setLastOccurredAt(AT.minusSeconds(1800));
        topic.setVersion(version);
        return topic;
    }

    private static AiTopicItemEntity item(UUID topicId) {
        AiTopicItemEntity item = new AiTopicItemEntity();
        item.setId(UUID.randomUUID()); item.setTopicId(topicId); item.setMessageId(UUID.randomUUID());
        item.setOccurredAt(AT.minusSeconds(3600)); item.setChannelType("email");
        return item;
    }

    private static String json(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .writeValueAsString(value instanceof List<?> ? value : List.of(value));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
