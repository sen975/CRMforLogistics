package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicItemMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AiTopicReviewPendingServiceTest {
    @Test
    void pendingTopicsAreLoadedSeparatelyFromReadyTimeline() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicItemMapper items = mock(AiTopicItemMapper.class);
        AiTopicEntity pending = new AiTopicEntity();
        pending.setId(UUID.randomUUID());
        pending.setOwnerType("CONTACT");
        pending.setOwnerId(contactId);
        pending.setContactId(contactId);
        pending.setStatus("REVIEW_PENDING");
        pending.setTitle("待确认");
        pending.setAiSummary("候选摘要");
        pending.setReviewOrigin("MERGE_SOURCE");
        pending.setReviewSourceTopicId(UUID.randomUUID());
        pending.setReviewSourceTopicTitle("历史报价");
        pending.setVersion(1L);
        when(topics.listReviewPendingForContact(contactId)).thenReturn(List.of(pending));

        when(items.listByTopic(pending.getId())).thenReturn(List.of());
        AiTopicService service = new AiTopicService(null, null, topics, items, null, null, null, null);

        AiTopicModels.TopicProjection projection = service.getReviewPendingTopics(userId, contactId).get(0);
        assertThat(projection.reviewOrigin()).isEqualTo(AiTopicModels.TopicReviewOrigin.MERGE_SOURCE);
        assertThat(projection.reviewSourceTopicTitle()).isEqualTo("历史报价");
        verify(topics).listReviewPendingForContact(contactId);
        verify(topics, never()).listReady(contactId);
    }

    @Test
    void mapperAndGenerationContextUseReadyOnlyContract() throws Exception {
        String mapper = Files.readString(Path.of("src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicMapper.java"));
        String itemMapper = Files.readString(Path.of("src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicItemMapper.java"));
        assertThat(mapper).contains("status='READY'");
        assertThat(mapper).contains("status='REVIEW_PENDING'");
        assertThat(mapper).contains("review_source_topic_title", "left join ai_topics source_topic");
        assertThat(itemMapper).contains("status='READY'");
        assertThat(mapper).contains("listReadyByOwner");
    }
}
