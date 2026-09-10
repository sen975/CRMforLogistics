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
        String text = WeComUserNotificationText.render(
                row.getChannelType(), row.getContactLabel(), row.getMessageCount(),
                row.getLastPreview());
        try {
            sendService.send(row.getAuthCorpId(), row.getAgentId(),
                    row.getRecipientWecomUserId(), text);
            notificationMapper.markSent(row.getId(), clock.instant());
        } catch (WeComException e) {
            recordFailure(row, e.code());
        } catch (RuntimeException e) {
            recordFailure(row, "WECOM_NOTIFICATION_SEND_FAILED");
        }
    }

    private void recordFailure(WeComUserNotificationEntity row, String errorCode) {
        int attempts = row.getAttemptCount() == null ? 0 : row.getAttemptCount();
        if (attempts + 1 < maxAttempts) {
            notificationMapper.retryLater(row.getId(),
                    clock.instant().plus(retryBackoff), errorCode);
            LOG.warn("event=wecom.notification_retry notificationId={} attempt={} code={}",
                    row.getId(), attempts + 1, errorCode);
            return;
        }
        notificationMapper.markFailed(row.getId(), errorCode);
        LOG.error("event=wecom.notification_failed notificationId={} code={}",
                row.getId(), errorCode);
    }
}
