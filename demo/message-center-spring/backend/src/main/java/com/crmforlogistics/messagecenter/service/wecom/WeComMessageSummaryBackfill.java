package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataMessageEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank() and '${app.wecom-message-summary-enabled:false}' == 'true'")
public class WeComMessageSummaryBackfill {
    private final AppConfig config;
    private final WeComInstallationService installations;
    private final WeComChatDataMessageMapper messages;
    private final WeComMessageSummaryRepository repository;

    public WeComMessageSummaryBackfill(AppConfig config, WeComInstallationService installations,
                                       WeComChatDataMessageMapper messages,
                                       WeComMessageSummaryRepository repository) {
        this.config = config;
        this.installations = installations;
        this.messages = messages;
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${app.wecom-message-summary-backfill-interval-seconds:600}000",
            initialDelay = 5000)
    public int scheduledRun() {
        if (config.localDevMode() || config.wecomSuiteId() == null || config.wecomSuiteId().isBlank()
                || config.wecomLoginAuthCorpId() == null || config.wecomLoginAuthCorpId().isBlank()) return 0;
        return runOnce(UUID.fromString(installations.resolveInstallation(
                config.wecomSuiteId(), config.wecomLoginAuthCorpId()).installationId()), Instant.now());
    }

    public int runOnce(UUID installationId, Instant now) {
        if (installationId == null || now == null) throw new IllegalArgumentException("backfill arguments invalid");
        int limit = config.wecomMessageSummaryBackfillBatchSize();
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("backfill batch size invalid");
        ResolvedInstallation installation = installations.resolveInstallation(
                config.wecomSuiteId(), config.wecomLoginAuthCorpId());
        int enqueued = 0;
        for (WeComChatDataMessageEntity message : messages.findForSummaryBackfill(installationId, limit)) {
            if (message == null || message.getMsgid() == null || message.getSendTime() == null) continue;
            String request = "{\"operation\":\"submit\",\"msgid\":\""
                    + escapeJson(message.getMsgid()) + "\"}";
            if (repository.enqueueIfAbsent(new WeComMessageSummaryRepository.EnqueueCommand(
                    installationId, installation.authCorpId(), message.getSourceConversationId(),
                    message.getMsgid(), message.getSendTime(), request, now))) enqueued++;
        }
        return enqueued;
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
