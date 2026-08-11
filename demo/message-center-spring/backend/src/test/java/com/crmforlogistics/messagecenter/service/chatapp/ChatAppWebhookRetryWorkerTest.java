package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelEventEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelEventMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatAppWebhookRetryWorkerTest {
    @Test
    void fifthProjectionFailureMovesEventToDead() {
        ChannelEventMapper eventMapper = mock(ChannelEventMapper.class);
        ChatAppWebhookProjector projector = mock(ChatAppWebhookProjector.class);
        ChannelEventEntity event = new ChannelEventEntity();
        event.setId(UUID.randomUUID());
        event.setAttemptCount(4);
        when(eventMapper.claimDue(any(), any(), eq(20))).thenReturn(List.of(event));
        when(projector.project(event)).thenThrow(new IllegalStateException("projection failed"));

        new ChatAppWebhookRetryWorker(eventMapper, projector).retry();

        verify(eventMapper).markDead(
                event.getId(), "PROJECTION_FAILED", "projection failed");
    }
}
