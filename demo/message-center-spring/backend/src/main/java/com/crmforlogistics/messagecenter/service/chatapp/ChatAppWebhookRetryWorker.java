package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelEventEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelEventMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.chatapp-webhook-worker-enabled", havingValue = "true", matchIfMissing = true)
public class ChatAppWebhookRetryWorker {
    private final ChannelEventMapper eventMapper;
    private final ChatAppWebhookProjector projector;

    public ChatAppWebhookRetryWorker(ChannelEventMapper eventMapper,
                                     ChatAppWebhookProjector projector) {
        this.eventMapper = eventMapper;
        this.projector = projector;
    }

    @Scheduled(fixedDelay = 5000)
    public void retry() {
        var events = eventMapper.claimDue(
                "chatapp-inbox-" + UUID.randomUUID(), Instant.now().plusSeconds(30), 20);
        for (ChannelEventEntity event : events) {
            try {
                projector.project(event);
            } catch (RuntimeException error) {
                int attempts = event.getAttemptCount() == null ? 1 : event.getAttemptCount() + 1;
                String message = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
                if (attempts >= 5) {
                    eventMapper.markDead(event.getId(), "PROJECTION_FAILED", message);
                } else {
                    long delay = Math.min(300L, 10L * (1L << Math.min(attempts - 1, 5)));
                    eventMapper.markRetry(event.getId(), Instant.now().plusSeconds(delay),
                            "PROJECTION_FAILED", message);
                }
            }
        }
    }
}
