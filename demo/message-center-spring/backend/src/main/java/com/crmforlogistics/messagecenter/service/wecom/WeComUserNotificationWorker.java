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
            try {
                if (notificationMapper.claim(row.getId()) != 1) {
                    continue;
                }
                dispatch(row);
            } catch (RuntimeException e) {
                // One bad row must not stop the rest of the batch; recoverStuck requeues it later.
                LOG.warn("event=wecom.notification_row_failed notificationId={}", row.getId(), e);
            }
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
        boolean terminal = attempts + 1 >= maxAttempts;
        try {
            if (terminal) {
                notificationMapper.markFailed(row.getId(), errorCode);
            } else {
                notificationMapper.retryLater(row.getId(),
                        clock.instant().plus(retryBackoff), errorCode);
            }
        } catch (RuntimeException persistenceFailure) {
            // The row stays SENDING; recoverStuck requeues it later.
            if (terminal) {
                // A row that spent its retry budget could not be closed out - it will be re-sent.
                LOG.error("event=wecom.notification_record_failure_failed notificationId={} code={}",
                        row.getId(), errorCode, persistenceFailure);
            } else {
                LOG.warn("event=wecom.notification_record_failure_failed notificationId={} code={}",
                        row.getId(), errorCode, persistenceFailure);
            }
            return;
        }
        if (terminal) {
            LOG.error("event=wecom.notification_failed notificationId={} code={} upstreamCode={}",
                    row.getId(), errorCode, upstreamCode, cause);
        } else {
            LOG.warn("event=wecom.notification_retry notificationId={} attempt={} code={} upstreamCode={}",
                    row.getId(), attempts + 1, errorCode, upstreamCode, cause);
        }
    }
}
