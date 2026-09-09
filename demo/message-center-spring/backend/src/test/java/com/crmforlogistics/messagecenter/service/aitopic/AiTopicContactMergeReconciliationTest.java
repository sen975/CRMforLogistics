package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.entity.AiTopicItemEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicGenerationAttemptMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicGenerationJobMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicItemMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicReviewMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicVersionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTopicContactMergeReconciliationTest {
    @Test
    void identicalCrossChannelTopicsFuseWithoutCallingAi() {
        UUID userId = UUID.randomUUID();
        UUID sourceContactId = UUID.randomUUID();
        UUID targetContactId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicItemMapper items = mock(AiTopicItemMapper.class);
        AiTopicVersionMapper versions = mock(AiTopicVersionMapper.class);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        AiTopicEntity first = topic(firstId, targetContactId, "  报价  ", "报价摘要", "READY", 1L,
                Instant.parse("2026-08-01T00:00:00Z"));
        AiTopicEntity second = topic(secondId, targetContactId, "报价", " 报价摘要 ", "REVIEW_PENDING", 2L,
                Instant.parse("2026-08-02T00:00:00Z"));
        AiTopicItemEntity firstItem = item(firstId, "email", Instant.parse("2026-08-01T01:00:00Z"));
        AiTopicItemEntity secondItem = item(secondId, "phone", Instant.parse("2026-08-02T01:00:00Z"));
        when(topics.listContactMergeCandidates(targetContactId)).thenReturn(List.of(first, second));
        when(items.listByTopic(firstId)).thenReturn(List.of(firstItem));
        when(items.listByTopic(secondId)).thenReturn(List.of(secondItem));
        when(items.listByTopic(any(UUID.class))).thenAnswer(invocation -> {
            UUID topicId = invocation.getArgument(0);
            if (firstId.equals(topicId)) return List.of(firstItem);
            if (secondId.equals(topicId)) return List.of(secondItem);
            return List.of();
        });

        AiTopicService service = service(topics, items, versions, null, gateway);

        service.reconcileAfterContactMerge(sourceContactId, targetContactId, userId);

        verify(gateway, never()).fuse(any());
        verify(topics).insert(any(AiTopicEntity.class));
        verify(topics).archiveForFusion(firstId);
        verify(topics).archiveForFusion(secondId);
        verify(items, times(2)).updateById(any(AiTopicItemEntity.class));
    }

    @Test
    void sameTitleWithDifferentSummaryCallsAiFusion() {
        UUID userId = UUID.randomUUID();
        UUID sourceContactId = UUID.randomUUID();
        UUID targetContactId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicItemMapper items = mock(AiTopicItemMapper.class);
        AiTopicVersionMapper versions = mock(AiTopicVersionMapper.class);
        AiTopicReviewMapper reviews = mock(AiTopicReviewMapper.class);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        Instant firstAt = Instant.parse("2026-08-01T00:00:00Z");
        Instant secondAt = Instant.parse("2026-08-02T00:00:00Z");
        AiTopicEntity first = topic(firstId, targetContactId, "报价", "摘要一", "READY", 1L, firstAt);
        AiTopicEntity second = topic(secondId, targetContactId, " 报价 ", "摘要二", "REVIEW_PENDING", 1L, secondAt);
        AiTopicItemEntity firstItem = item(firstId, "email", firstAt.plusSeconds(60));
        AiTopicItemEntity secondItem = item(secondId, "phone", secondAt.plusSeconds(60));
        when(topics.listContactMergeCandidates(targetContactId)).thenReturn(List.of(first, second));
        when(items.listByTopic(any(UUID.class))).thenAnswer(invocation -> {
            UUID topicId = invocation.getArgument(0);
            if (firstId.equals(topicId)) return List.of(firstItem);
            if (secondId.equals(topicId)) return List.of(secondItem);
            return List.of();
        });
        when(reviews.listTopicSources(List.of(firstId, secondId))).thenReturn(List.of(
                new AiTopicReviewMapper.SourceRow(firstItem.getMessageId(), "MESSAGE", "email", firstItem.getOccurredAt(), "", "内容一"),
                new AiTopicReviewMapper.SourceRow(secondItem.getMessageId(), "MESSAGE", "phone", secondItem.getOccurredAt(), "", "内容二")));
        when(gateway.fuse(any())).thenReturn(new AiTopicModels.GenerationOutput(List.of(
                new AiTopicModels.TopicAssignment("fusion", "报价综合", "AI 融合摘要", 1d,
                        List.of(firstItem.getMessageId(), secondItem.getMessageId())))));

        AiTopicService service = service(topics, items, versions, reviews, gateway);

        service.reconcileAfterContactMerge(sourceContactId, targetContactId, userId);

        verify(gateway).fuse(any());
    }

    @Test
    void storedAndGroupTopicsAreExcludedByTheCandidateContract() {
        UUID userId = UUID.randomUUID();
        UUID sourceContactId = UUID.randomUUID();
        UUID targetContactId = UUID.randomUUID();
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicItemMapper items = mock(AiTopicItemMapper.class);
        AiTopicVersionMapper versions = mock(AiTopicVersionMapper.class);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        when(topics.listContactMergeCandidates(targetContactId)).thenReturn(List.of());

        AiTopicService service = service(topics, items, versions, null, gateway);

        service.reconcileAfterContactMerge(sourceContactId, targetContactId, userId);

        verify(gateway, never()).fuse(any());
        verify(topics, never()).insert(any(AiTopicEntity.class));
    }

    private static AiTopicService service(AiTopicMapper topics, AiTopicItemMapper items,
                                           AiTopicVersionMapper versions, AiTopicReviewMapper reviews,
                                           TopicAiGateway gateway) {
        return new AiTopicService(mock(com.crmforlogistics.messagecenter.service.contact.ContactService.class),
                mock(AiTopicInputService.class), topics, items, mock(AiTopicGenerationJobMapper.class),
                versions, new AiTopicConfigHolder(new AiTopicConfig("", "", "model", 30, 200, 262144,
                        .65, 1, 3, 120, 30)), mock(ContactIdentityMapper.class), null, null, null, null,
                mock(AiTopicGenerationAttemptMapper.class), reviews, gateway);
    }

    private static AiTopicEntity topic(UUID id, UUID contactId, String title, String summary,
                                       String status, long version, Instant at) {
        AiTopicEntity topic = new AiTopicEntity();
        topic.setId(id);
        topic.setOwnerType("CONTACT");
        topic.setOwnerId(contactId);
        topic.setContactId(contactId);
        topic.setStatus(status);
        topic.setTitle(title);
        topic.setAiSummary(summary);
        topic.setFirstOccurredAt(at);
        topic.setLastOccurredAt(at);
        topic.setVersion(version);
        return topic;
    }

    private static AiTopicItemEntity item(UUID topicId, String channel, Instant at) {
        AiTopicItemEntity item = new AiTopicItemEntity();
        item.setId(UUID.randomUUID());
        item.setTopicId(topicId);
        item.setMessageId(UUID.randomUUID());
        item.setOccurredAt(at);
        item.setChannelType(channel);
        return item;
    }
}
