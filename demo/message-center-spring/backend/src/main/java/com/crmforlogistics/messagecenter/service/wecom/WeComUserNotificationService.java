package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.entity.WeComUserBindingEntity;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.WeComUserNotificationEntity;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.WeComUserBindingMapper;
import com.crmforlogistics.messagecenter.mapper.WeComUserNotificationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 把一条非企业微信渠道的入站消息折算成一条待发的企微应用消息提醒。
 * 解析失败一律吞掉：这个调用点位于入站消息落库事务内，不能反过来影响消息入库。
 */
@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComUserNotificationService {
    private static final Logger LOG = LoggerFactory.getLogger(WeComUserNotificationService.class);

    private final AppConfig config;
    private final WeComUserNotificationMapper notificationMapper;
    private final ConversationMapper conversationMapper;
    private final WeComUserBindingMapper bindingMapper;
    private final WeComInstallationService installationService;
    private final Clock clock;

    public WeComUserNotificationService(AppConfig config,
                                        WeComUserNotificationMapper notificationMapper,
                                        ConversationMapper conversationMapper,
                                        WeComUserBindingMapper bindingMapper,
                                        WeComInstallationService installationService,
                                        Clock clock) {
        this.config = config;
        this.notificationMapper = notificationMapper;
        this.conversationMapper = conversationMapper;
        this.bindingMapper = bindingMapper;
        this.installationService = installationService;
        this.clock = clock;
    }

    public void enqueueInbound(InboundMessage message) {
        if (!config.wecomUserNotificationEnabled()) {
            return;
        }
        try {
            WeComUserNotificationEntity row = buildRow(message);
            if (row == null) {
                return;
            }
            notificationMapper.upsertPending(row);
        } catch (RuntimeException e) {
            LOG.warn("event=wecom.notification_enqueue_failed conversationId={} channelType={}",
                    message.conversationId(), message.channelType(), e);
        }
    }

    private WeComUserNotificationEntity buildRow(InboundMessage message) {
        ConversationEntity conversation = conversationMapper.selectById(message.conversationId());
        UUID assignee = conversation == null ? null : conversation.getAssignedUserId();
        if (assignee == null) {
            LOG.debug("event=wecom.notification_skipped "
                            + "code=WECOM_NOTIFICATION_RECIPIENT_UNBOUND reason=no_assignee conversationId={}",
                    message.conversationId());
            return null;
        }
        Optional<WeComUserBindingEntity> binding = bindingMapper.findByUserId(assignee);
        if (binding.isEmpty()) {
            LOG.debug("event=wecom.notification_skipped "
                            + "code=WECOM_NOTIFICATION_RECIPIENT_UNBOUND reason=assignee_unbound "
                            + "conversationId={} userId={}",
                    message.conversationId(), assignee);
            return null;
        }
        WeComUserBindingEntity bound = binding.get();
        WeComInstallationEntity installation =
                installationService.find(bound.getSuiteId(), bound.getAuthCorpId());
        if (installation == null || installation.getAgentId() == null
                || installation.getAgentId().isBlank()) {
            LOG.debug("event=wecom.notification_skipped "
                            + "code=WECOM_NOTIFICATION_RECIPIENT_UNBOUND reason=installation_unresolved "
                            + "conversationId={} authCorpId={}",
                    message.conversationId(), bound.getAuthCorpId());
            return null;
        }

        Instant now = clock.instant();
        WeComUserNotificationEntity row = new WeComUserNotificationEntity();
        row.setId(UUID.randomUUID());
        row.setConversationId(message.conversationId());
        row.setChannelAccountId(message.channelAccountId());
        row.setRecipientUserId(assignee);
        row.setRecipientWecomUserId(bound.getWecomUserId());
        row.setAuthCorpId(bound.getAuthCorpId());
        row.setAgentId(installation.getAgentId());
        row.setChannelType(message.channelType());
        row.setContactLabel(message.contactLabel() == null ? "" : message.contactLabel());
        row.setMessageCount(1);
        row.setLastPreview(
                WeComUserNotificationText.truncatePreview(message.bodyText(), message.subject()));
        row.setFirstMessageAt(message.occurredAt());
        row.setSendAfter(now.plusMillis(config.wecomUserNotificationWindowMs()));
        row.setStatus("PENDING");
        row.setAttemptCount(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setVersion(0L);
        return row;
    }

    public record InboundMessage(UUID conversationId, UUID channelAccountId, String channelType,
                                 String contactLabel, String subject, String bodyText,
                                 Instant occurredAt) {}
}
