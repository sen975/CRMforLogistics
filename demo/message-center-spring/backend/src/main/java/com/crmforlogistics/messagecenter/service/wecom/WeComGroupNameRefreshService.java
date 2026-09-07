package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.entity.WeComGroupNameRefreshJobEntity;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.WeComGroupNameRefreshJobMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComGroupNameRefreshService {
    static final Duration AUTOMATIC_COOLDOWN = Duration.ofHours(24);
    private final ConversationMapper conversations;
    private final WeComGroupNameRefreshJobMapper jobs;
    private final WeComExternalContactService externalContacts;

    public WeComGroupNameRefreshService(ConversationMapper conversations, WeComGroupNameRefreshJobMapper jobs,
                                        WeComExternalContactService externalContacts) {
        this.conversations = conversations;
        this.jobs = jobs;
        this.externalContacts = externalContacts;
    }

    public WeComGroupNameRefreshJobEntity requestManual(UUID userId, UUID sourceConversationId) {
        if (conversations.findAccessibleWeComGroup(userId, sourceConversationId) == null) {
            throw new WeComException("WECOM_GROUP_NOT_ACCESSIBLE", 403, "当前账号无权刷新该群昵称");
        }
        Instant now = Instant.now();
        jobs.insertManualIfAbsent(sourceConversationId, userId, now);
        WeComGroupNameRefreshJobEntity job = jobs.findActive(sourceConversationId);
        if (job == null) throw new IllegalStateException("WECOM_GROUP_NAME_REFRESH_NOT_QUEUED");
        return job;
    }

    public void requestAfterTopicUpdated(UUID sourceConversationId) {
        Instant now = Instant.now();
        jobs.insertAutomaticIfDue(sourceConversationId, now.minus(AUTOMATIC_COOLDOWN), now);
    }

    String lookup(ResolvedInstallation installation, String chatId) {
        return externalContacts.groupNameForSync(installation, chatId);
    }

    static boolean isAutomaticRefreshDue(Instant lastCheckedAt, Instant now) {
        return lastCheckedAt == null || !lastCheckedAt.isAfter(now.minus(AUTOMATIC_COOLDOWN));
    }

    static FailureDisposition emptyNameDisposition() { return FailureDisposition.UNAVAILABLE; }

    static FailureDisposition classifyFailure(String code, int httpStatus) {
        if (httpStatus == 403 || httpStatus == 404 || "WECOM_API_PERMISSION_DENIED".equals(code)
                || "WECOM_INSTALLATION_NOT_FOUND".equals(code)) {
            return FailureDisposition.UNAVAILABLE;
        }
        return FailureDisposition.RETRY;
    }

    enum FailureDisposition { RETRY, UNAVAILABLE }
}
