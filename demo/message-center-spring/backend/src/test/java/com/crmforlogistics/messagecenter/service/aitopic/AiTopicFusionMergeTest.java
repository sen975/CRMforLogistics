package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.entity.AiTopicItemEntity;
import com.crmforlogistics.messagecenter.mapper.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiTopicFusionMergeTest {
    @Test
    void mergeCreatesFreshTopicArchivesSourcesAndMovesAllItems() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicItemMapper items = mock(AiTopicItemMapper.class);
        AiTopicVersionMapper versions = mock(AiTopicVersionMapper.class);
        AiTopicReviewMapper reviews = mock(AiTopicReviewMapper.class);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        AiTopicEntity first = topic(firstId, contactId, "报价", "报价讨论", Instant.parse("2026-08-01T00:00:00Z"));
        AiTopicEntity second = topic(secondId, contactId, "交期", "交期讨论", Instant.parse("2026-08-02T00:00:00Z"));
        AiTopicItemEntity firstItem = item(firstId, Instant.parse("2026-08-01T01:00:00Z"));
        AiTopicItemEntity secondItem = item(secondId, Instant.parse("2026-08-02T01:00:00Z"));
        when(topics.selectById(firstId)).thenReturn(first);
        when(topics.selectById(secondId)).thenReturn(second);
        when(items.listByTopic(firstId)).thenReturn(List.of(firstItem));
        when(items.listByTopic(secondId)).thenReturn(List.of(secondItem));
        when(reviews.listTopicSources(anyList())).thenReturn(List.of(
                source(firstItem.getMessageId(), "报价"), source(secondItem.getMessageId(), "交期")));
        when(gateway.fuse(any())).thenReturn(new AiTopicModels.GenerationOutput(List.of(
                new AiTopicModels.TopicAssignment("fusion", "报价与交期", "融合后的完整摘要", 1,
                        List.of(firstItem.getMessageId(), secondItem.getMessageId())))));
        when(topics.insert(any(AiTopicEntity.class))).thenAnswer(invocation -> {
            AiTopicEntity created = invocation.getArgument(0);
            assertThat(created.getId()).isNotIn(firstId, secondId);
            assertThat(created.getInputFingerprint()).matches("[0-9a-f]{64}");
            return 1;
        });

        AiTopicService service = new AiTopicService(mock(com.crmforlogistics.messagecenter.service.contact.ContactService.class),
                mock(AiTopicInputService.class), topics, items, mock(AiTopicGenerationJobMapper.class),
                versions, new AiTopicConfigHolder(new AiTopicConfig("", "", "model", 30, 200, 262144, .65, 1, 3, 120, 30)),
                mock(ContactIdentityMapper.class), null, null, null, null, mock(AiTopicGenerationAttemptMapper.class),
                reviews, gateway);

        List<AiTopicModels.TopicProjection> result = service.mergeTopics(userId, List.of(firstId, secondId),
                Map.of(firstId, 1L, secondId, 1L));

        assertThat(result).hasSize(1);
        UUID mergedId = result.get(0).id();
        assertThat(mergedId).isNotIn(firstId, secondId);
        verify(topics).insert(any(AiTopicEntity.class));
        verify(topics).archiveForFusion(firstId);
        verify(topics).archiveForFusion(secondId);
        verify(items, times(2)).updateById(any(AiTopicItemEntity.class));
        assertThat(result.get(0).title()).isEqualTo("报价与交期");
        assertThat(result.get(0).summary()).isEqualTo("融合后的完整摘要");
        verify(versions).insertVersion(eq(mergedId), eq(1L), eq("MERGED"), anyString(), anyString(), contains(firstId.toString()), eq(userId));
        verify(versions).insertVersion(eq(firstId), eq(2L), eq("MERGED_INTO"), anyString(), anyString(), contains(mergedId.toString()), eq(userId));
        verify(versions).insertVersion(eq(secondId), eq(2L), eq("MERGED_INTO"), anyString(), anyString(), contains(mergedId.toString()), eq(userId));
    }

    private static AiTopicEntity topic(UUID id, UUID contactId, String title, String summary, Instant at) {
        AiTopicEntity topic = new AiTopicEntity();
        topic.setId(id); topic.setOwnerType("CONTACT"); topic.setOwnerId(contactId); topic.setContactId(contactId);
        topic.setStatus("READY"); topic.setTitle(title); topic.setAiSummary(summary); topic.setFirstOccurredAt(at);
        topic.setLastOccurredAt(at); topic.setVersion(1L);
        return topic;
    }

    private static AiTopicItemEntity item(UUID topicId, Instant at) {
        AiTopicItemEntity item = new AiTopicItemEntity(); item.setId(UUID.randomUUID()); item.setTopicId(topicId);
        item.setMessageId(UUID.randomUUID()); item.setOccurredAt(at); item.setChannelType("email"); return item;
    }

    private static AiTopicReviewMapper.SourceRow source(UUID id, String text) {
        return new AiTopicReviewMapper.SourceRow(id, UUID.randomUUID(), "MESSAGE", "email",
                Instant.parse("2026-08-02T00:00:00Z"), "inbound", "", text);
    }
}
