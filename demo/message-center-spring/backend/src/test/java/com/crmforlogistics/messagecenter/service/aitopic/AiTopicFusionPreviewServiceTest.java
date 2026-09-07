package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import com.crmforlogistics.messagecenter.dto.request.AiTopicFusionRequest;
import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
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

class AiTopicFusionPreviewServiceTest {
    @Test
    void previewUsesDedicatedFusionGatewayAndPersistsVersionSnapshot() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID firstSource = UUID.randomUUID();
        UUID secondSource = UUID.randomUUID();
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicItemMapper items = mock(AiTopicItemMapper.class);
        AiTopicReviewMapper reviews = mock(AiTopicReviewMapper.class);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        when(topics.selectById(firstId)).thenReturn(topic(firstId, contactId, 2L));
        when(topics.selectById(secondId)).thenReturn(topic(secondId, contactId, 3L));
        when(reviews.listTopicSources(anyList())).thenReturn(List.of(
                source(firstSource, "报价"), source(secondSource, "交付")));
        when(gateway.fuse(any())).thenReturn(new AiTopicModels.GenerationOutput(List.of(
                new AiTopicModels.TopicAssignment("fusion", "订单履约", "融合报价与交付进展", 1,
                        List.of(firstSource, secondSource)))));

        AiTopicService service = service(topics, items, reviews, gateway);
        var result = service.previewTopicFusion(userId, contactId,
                new AiTopicFusionRequest(List.of(firstId, secondId), Map.of(firstId, 2L, secondId, 3L)));

        assertThat(result.title()).isEqualTo("订单履约");
        assertThat(result.summary()).isEqualTo("融合报价与交付进展");
        assertThat(result.sourceCount()).isEqualTo(2);
        verify(gateway).fuse(argThat(input -> input.sources().size() == 2 && input.existingTopics().size() == 2));
        verify(reviews).insertFusionPreview(any(), eq("CONTACT"), eq(contactId), contains(firstId.toString()),
                contains("订单履约"), contains(firstId.toString()), eq(userId), any());
    }

    private static AiTopicService service(AiTopicMapper topics, AiTopicItemMapper items,
                                          AiTopicReviewMapper reviews, TopicAiGateway gateway) {
        return new AiTopicService(mock(ContactService.class), mock(AiTopicInputService.class), topics, items,
                mock(AiTopicGenerationJobMapper.class), mock(AiTopicVersionMapper.class),
                new AiTopicConfigHolder(new AiTopicConfig("", "", "model", 30, 200, 262144, .65, 1, 3, 120, 30)),
                mock(ContactIdentityMapper.class), mock(AiTopicOperationJobMapper.class),
                mock(AiTopicInboxRequestMapper.class), null, null, mock(AiTopicGenerationAttemptMapper.class),
                reviews, gateway);
    }

    private static AiTopicEntity topic(UUID id, UUID contactId, long version) {
        AiTopicEntity topic = new AiTopicEntity();
        topic.setId(id); topic.setContactId(contactId); topic.setOwnerType("CONTACT"); topic.setOwnerId(contactId);
        topic.setStatus("READY"); topic.setTitle("主题 " + id); topic.setAiSummary("已有摘要");
        topic.setFirstOccurredAt(Instant.parse("2026-08-01T00:00:00Z"));
        topic.setLastOccurredAt(Instant.parse("2026-08-02T00:00:00Z")); topic.setVersion(version);
        return topic;
    }

    private static AiTopicReviewMapper.SourceRow source(UUID id, String text) {
        return new AiTopicReviewMapper.SourceRow(id, UUID.randomUUID(), "MESSAGE", "email",
                Instant.parse("2026-08-02T00:00:00Z"), "inbound", "", text);
    }
}
