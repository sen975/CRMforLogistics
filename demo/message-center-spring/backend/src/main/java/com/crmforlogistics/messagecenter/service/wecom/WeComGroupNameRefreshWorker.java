package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.entity.WeComGroupNameRefreshJobEntity;
import com.crmforlogistics.messagecenter.entity.WeComSourceConversationEntity;
import com.crmforlogistics.messagecenter.mapper.WeComGroupNameRefreshJobMapper;
import com.crmforlogistics.messagecenter.mapper.WeComInstallationMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceConversationMapper;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComGroupNameRefreshWorker {
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LEASE = Duration.ofMinutes(2);
    private final WeComGroupNameRefreshJobMapper jobs;
    private final WeComSourceConversationMapper conversations;
    private final WeComGroupNameRefreshService service;
    private final InstallationResolver installations;
    private final EventHub events;
    private final String workerId = "wecom-group-name-" + UUID.randomUUID();

    @Autowired
    public WeComGroupNameRefreshWorker(AppConfig config,
                                       WeComInstallationService installationService,
                                       WeComInstallationMapper installations,
                                       WeComGroupNameRefreshJobMapper jobs,
                                       WeComSourceConversationMapper conversations,
                                       WeComGroupNameRefreshService service,
                                       EventHub events) {
        this(jobs, conversations, service, installationId -> {
            WeComInstallationEntity installation = installations.selectById(installationId);
            if (installation == null) {
                throw new WeComException("WECOM_INSTALLATION_NOT_FOUND", 404, "企业微信安装不存在");
            }
            return installationService.resolveInstallation(config.wecomSuiteId(), installation.getAuthCorpId());
        }, events);
    }

    public WeComGroupNameRefreshWorker(WeComGroupNameRefreshJobMapper jobs,
                                       WeComSourceConversationMapper conversations,
                                       WeComGroupNameRefreshService service,
                                       InstallationResolver installations, EventHub events) {
        this.jobs = jobs;
        this.conversations = conversations;
        this.service = service;
        this.installations = installations;
        this.events = events;
    }

    public int runOnce(Instant now, int limit) {
        int processed = 0;
        for (WeComGroupNameRefreshJobEntity job : jobs.listRunnable(now, Math.min(20, Math.max(1, limit)))) {
            if (jobs.claim(job.getId(), workerId, now.plus(LEASE)) == 0) continue;
            process(job, now);
            processed++;
        }
        return processed;
    }

    private void process(WeComGroupNameRefreshJobEntity job, Instant now) {
        WeComSourceConversationEntity source = conversations.selectById(job.getSourceConversationId());
        String chatId = source == null ? null : groupChatId(source.getProviderConversationKey());
        if (source == null || !"GROUP".equals(source.getConversationType()) || chatId == null) {
            completeUnavailable(job, now, "GROUP_CONVERSATION_INVALID");
            return;
        }
        try {
            String name = service.lookup(installations.resolve(source.getInstallationId()), chatId);
            if (name == null || name.isBlank()) {
                completeUnavailable(job, now, "GROUP_NAME_UNAVAILABLE");
                return;
            }
            conversations.updateNameResolution(source.getId(), name.trim(), "RESOLVED", now, null, null);
            jobs.finish(job.getId(), workerId, "COMPLETED", null, null, now, now);
            publishCompleted(source.getId());
        } catch (WeComException failure) {
            if (WeComGroupNameRefreshService.classifyFailure(failure.code(), failure.httpStatus())
                    == WeComGroupNameRefreshService.FailureDisposition.UNAVAILABLE) {
                completeUnavailable(job, now, failure.code());
            } else {
                retryOrFail(job, source.getId(), now, failure.code());
            }
        } catch (RuntimeException failure) {
            retryOrFail(job, source.getId(), now, "WECOM_GROUP_NAME_REFRESH_RUNTIME");
        }
    }

    private void completeUnavailable(WeComGroupNameRefreshJobEntity job, Instant now, String code) {
        conversations.updateNameResolution(job.getSourceConversationId(), null, "UNAVAILABLE", now, null, code);
        jobs.finish(job.getId(), workerId, "UNAVAILABLE", code, code, now, now);
        publishCompleted(job.getSourceConversationId());
    }

    private void retryOrFail(WeComGroupNameRefreshJobEntity job, UUID sourceConversationId, Instant now, String code) {
        int attempts = job.getAttemptCount() == null ? 1 : job.getAttemptCount() + 1;
        if (attempts >= MAX_ATTEMPTS) {
            conversations.updateNameResolution(sourceConversationId, null, "RETRY_WAIT", now, null, code);
            jobs.finish(job.getId(), workerId, "FAILED", code, code, now, now);
            publishCompleted(sourceConversationId);
            return;
        }
        long delay = Math.min(900, 30L << Math.min(4, attempts - 1));
        Instant nextAttempt = now.plusSeconds(delay);
        conversations.updateNameResolution(sourceConversationId, null, "RETRY_WAIT", now, nextAttempt, code);
        jobs.finish(job.getId(), workerId, "RETRY_WAIT", code, code, nextAttempt, null);
    }

    private void publishCompleted(UUID sourceConversationId) {
        events.publish("wecom-group-name-refresh-completed", "{\"sourceConversationId\":\"" + sourceConversationId + "\"}");
    }

    private static String groupChatId(String key) {
        if (key == null || !key.startsWith("group:")) return null;
        String chatId = key.substring("group:".length()).trim();
        return chatId.isEmpty() || chatId.length() > 128 ? null : chatId;
    }

    @FunctionalInterface
    public interface InstallationResolver {
        ResolvedInstallation resolve(UUID installationId);
    }
}
