package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComSendService;
import com.crmforlogistics.messagecenter.entity.WeComUserNotificationEntity;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.mapper.WeComUserNotificationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
@ConditionalOnProperty(
        name = "app.wecom-user-notification-enabled",
        havingValue = "true",
        matchIfMissing = false)
public class WeComUserNotificationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(WeComUserNotificationWorker.class);
    private static final Duration STUCK_AFTER = Duration.ofSeconds(60);

    private final WeComUserNotificationMapper notificationMapper;
    private final WeComSendService sendService;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration retryBackoff;

    public WeComUserNotificationWorker(WeComUserNotificationMapper notificationMapper,
                                       WeComSendService sendService,
                                       Clock clock,
                                       @Value("${app.wecom-user-notification-max-attempts:3}")
                                       int maxAttempts,
                                       @Value("${app.wecom-user-notification-retry-backoff-seconds:10}")
                                       long retryBackoffSeconds) {
        this.notificationMapper = notificationMapper;
        this.sendService = sendService;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.retryBackoff = Duration.ofSeconds(retryBackoffSeconds);
    }

    public int recoverStuck() {
        List<UUID> recovered = notificationMapper.recoverStuck(
                clock.instant().minus(STUCK_AFTER));
        if (!recovered.isEmpty()) {
            LOG.warn("event=wecom.notification_stuck_recovered count={}", recovered.size());
        }
        return recovered.size();
    }

    public void runAvailable(String workerId, int batchSize) {
        Instant now = clock.instant();
        for (WeComUserNotificationEntity row : notificationMapper.listDue(now, batchSize)) {
            if (notificationMapper.claim(row.getId()) != 1) {
                continue;
            }
            dispatch(row);
        }
    }

    private void dispatch(WeComUserNotificationEntity row) {
        try {
            String text = WeComUserNotificationText.render(
                    row.getChannelType(), row.getContactLabel(), row.getMessageCount(),
                    row.getLastPreview());
            sendService.send(row.getAuthCorpId(), row.getAgentId(),
                    row.getRecipientWecomUserId(), text);
        } catch (RuntimeException e) {
            recordFailure(row, "WECOM_NOTIFICATION_SEND_FAILED", e);
            return;
        }
        try {
            notificationMapper.markSent(row.getId(), clock.instant());
        } catch (RuntimeException e) {
            // At-least-once semantics: the row stays SENDING and recoverStuck re-sends it.
            LOG.warn("event=wecom.notification_mark_sent_failed notificationId={}", row.getId(), e);
        }
    }

    private void recordFailure(WeComUserNotificationEntity row, String errorCode, Throwable cause) {
        int attempts = row.getAttemptCount() == null ? 0 : row.getAttemptCount();
        String upstreamCode = cause instanceof WeComException weComException
                ? weComException.code() : null;
        if (attempts + 1 < maxAttempts) {
            notificationMapper.retryLater(row.getId(),
                    clock.instant().plus(retryBackoff), errorCode);
            LOG.warn("event=wecom.notification_retry notificationId={} attempt={} code={} upstreamCode={}",
                    row.getId(), attempts + 1, errorCode, upstreamCode, cause);
            return;
        }
        notificationMapper.markFailed(row.getId(), errorCode);
        LOG.error("event=wecom.notification_failed notificationId={} code={} upstreamCode={}",
                row.getId(), errorCode, upstreamCode, cause);
    }
}
