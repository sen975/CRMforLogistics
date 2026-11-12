package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.ConditionalOnAssistantEnabled;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时批量标记过期；所有用户读写入口仍执行即时门禁。 */
@Component
@ConditionalOnAssistantEnabled
public class AssistantConversationExpiryScheduler {
    private final AssistantConversationLifecycleService lifecycle;

    public AssistantConversationExpiryScheduler(AssistantConversationLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Scheduled(fixedDelayString = "${assistant.conversation-expiry-scan-interval-ms:3600000}")
    public void expireInactiveConversations() {
        lifecycle.expireStale();
    }
}
