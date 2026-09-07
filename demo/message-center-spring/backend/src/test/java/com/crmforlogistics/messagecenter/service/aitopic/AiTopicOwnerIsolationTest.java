package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicItemMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTopicOwnerIsolationTest {

    @Test
    void rejectsForeignContactTopicWithoutFallingBackToGlobalIdLookup() {
        UUID userId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        AiTopicMapper topics = mock(AiTopicMapper.class);
        when(topics.findContactTopicByIdAndOwner(topicId, userId)).thenReturn(null);
        AiTopicService service = new AiTopicService(null, null, topics, mock(AiTopicItemMapper.class),
                null, null, null, null);

        assertThatThrownBy(() -> service.keepPending(userId, topicId))
                .isInstanceOf(AiTopicException.class);
        verify(topics, never()).selectById(topicId);
    }

    @Test
    void readsContactTopicOnlyThroughOwnerScopedLookup() {
        UUID userId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        AiTopicEntity topic = new AiTopicEntity();
        topic.setId(topicId);
        topic.setOwnerType("CONTACT");
        topic.setStatus("READY");
        AiTopicMapper topics = mock(AiTopicMapper.class);
        when(topics.findContactTopicByIdAndOwner(topicId, userId)).thenReturn(topic);
        AiTopicService service = new AiTopicService(null, null, topics, mock(AiTopicItemMapper.class),
                null, null, null, null);

        assertThatThrownBy(() -> service.keepPending(userId, topicId))
                .isInstanceOf(AiTopicException.class)
                .hasMessageContaining("TOPIC_REVIEW_CONFLICT");
        verify(topics, never()).selectById(topicId);
    }
}
