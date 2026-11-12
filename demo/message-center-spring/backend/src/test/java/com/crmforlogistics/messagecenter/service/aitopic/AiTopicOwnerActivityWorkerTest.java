package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.mapper.AiTopicOwnerActivityMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTopicOwnerActivityWorkerTest {
    @Test
    void dueOwnerCreatesAutomaticGenerationAndMarksItsActivityVersionGenerating() {
        AiTopicOwnerActivityMapper mapper = mock(AiTopicOwnerActivityMapper.class);
        AiTopicService topics = mock(AiTopicService.class);
        UUID ownerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-01T00:06:00Z");
        var row = new AiTopicOwnerActivityMapper.ActivityRow("CONTACT", ownerId,
                now.minusSeconds(360), now, 4L);
        when(mapper.listDue(now, 20)).thenReturn(List.of(row));
        when(mapper.lease(eq("CONTACT"), eq(ownerId), eq(4L), any(), any(), eq(now))).thenReturn(1);
        when(mapper.markGenerating(eq("CONTACT"), eq(ownerId), eq(4L), any())).thenReturn(1);

        new AiTopicOwnerActivityWorker(mapper, topics).runOnce(now, 20);

        verify(topics).enqueueAutomaticGeneration(new AiTopicOwnerService.OwnerRef("CONTACT", ownerId));
        verify(mapper).markGenerating(eq("CONTACT"), eq(ownerId), eq(4L), any());
    }
}
