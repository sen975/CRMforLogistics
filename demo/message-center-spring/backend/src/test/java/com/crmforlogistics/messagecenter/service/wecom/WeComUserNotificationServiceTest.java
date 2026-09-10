package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.channel.wecom.WeComUserBindingEntity;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.WeComUserNotificationEntity;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.WeComUserBindingMapper;
import com.crmforlogistics.messagecenter.mapper.WeComUserNotificationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WeComUserNotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");
    private static final UUID CONVERSATION = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID ASSIGNEE = UUID.fromString("30000000-0000-0000-0000-000000000003");

    private final WeComUserNotificationMapper notifications = mock(WeComUserNotificationMapper.class);
    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final WeComUserBindingMapper bindings = mock(WeComUserBindingMapper.class);
    private final WeComInstallationService installations = mock(WeComInstallationService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void enqueuesAPendingRowForTheBoundAssignee() {
        AppConfig config = config(true, 90_000L);
        when(conversations.selectById(CONVERSATION)).thenReturn(conversation(ASSIGNEE));
        when(bindings.findByUserId(ASSIGNEE)).thenReturn(Optional.of(binding("zhangsan")));
        when(installations.find("suite-1", "corp-1")).thenReturn(installation("1000002"));
        WeComUserNotificationService service =
                new WeComUserNotificationService(config, notifications, conversations, bindings,
                        installations, clock);

        service.enqueueInbound(inbound("张三", "运费问题", "你好，想问下运费"));

        ArgumentCaptor<WeComUserNotificationEntity> captor =
                ArgumentCaptor.forClass(WeComUserNotificationEntity.class);
        verify(notifications).upsertPending(captor.capture());
        WeComUserNotificationEntity row = captor.getValue();
        assertThat(row.getConversationId()).isEqualTo(CONVERSATION);
        assertThat(row.getChannelAccountId()).isEqualTo(ACCOUNT);
        assertThat(row.getRecipientUserId()).isEqualTo(ASSIGNEE);
        assertThat(row.getRecipientWecomUserId()).isEqualTo("zhangsan");
        assertThat(row.getAuthCorpId()).isEqualTo("corp-1");
        assertThat(row.getAgentId()).isEqualTo("1000002");
        assertThat(row.getChannelType()).isEqualTo("chatapp");
        assertThat(row.getContactLabel()).isEqualTo("张三");
        assertThat(row.getMessageCount()).isEqualTo(1);
        assertThat(row.getLastPreview()).isEqualTo("你好，想问下运费");
        assertThat(row.getFirstMessageAt()).isEqualTo(NOW.minusSeconds(5));
        assertThat(row.getSendAfter()).isEqualTo(NOW.plusSeconds(90));
    }

    @Test
    void writesNothingWhenTheNotificationFeatureIsDisabled() {
        WeComUserNotificationService service = new WeComUserNotificationService(
                config(false, 90_000L), notifications, conversations, bindings, installations, clock);

        service.enqueueInbound(inbound("张三", "主题", "正文"));

        verifyNoInteractions(notifications, conversations, bindings, installations);
    }

    @Test
    void writesNothingWhenTheConversationHasNoAssignee() {
        when(conversations.selectById(CONVERSATION)).thenReturn(conversation(null));
        WeComUserNotificationService service = service();

        service.enqueueInbound(inbound("张三", "主题", "正文"));

        verify(notifications, never()).upsertPending(any());
    }

    @Test
    void writesNothingWhenTheAssigneeHasNoWeComBinding() {
        when(conversations.selectById(CONVERSATION)).thenReturn(conversation(ASSIGNEE));
        when(bindings.findByUserId(ASSIGNEE)).thenReturn(Optional.empty());
        WeComUserNotificationService service = service();

        service.enqueueInbound(inbound("张三", "主题", "正文"));

        verify(notifications, never()).upsertPending(any());
    }

    @Test
    void writesNothingWhenTheInstallationCannotBeResolved() {
        when(conversations.selectById(CONVERSATION)).thenReturn(conversation(ASSIGNEE));
        when(bindings.findByUserId(ASSIGNEE)).thenReturn(Optional.of(binding("zhangsan")));
        when(installations.find("suite-1", "corp-1")).thenReturn(null);
        WeComUserNotificationService service = service();

        service.enqueueInbound(inbound("张三", "主题", "正文"));

        verify(notifications, never()).upsertPending(any());
    }

    @Test
    void swallowsRepositoryFailuresSoInboundPersistenceIsNeverBroken() {
        when(conversations.selectById(CONVERSATION))
                .thenThrow(new IllegalStateException("db down"));
        WeComUserNotificationService service = service();

        service.enqueueInbound(inbound("张三", "主题", "正文"));

        verify(notifications, never()).upsertPending(any());
    }

    private WeComUserNotificationService service() {
        return new WeComUserNotificationService(
                config(true, 90_000L), notifications, conversations, bindings, installations, clock);
    }

    private static AppConfig config(boolean enabled, long windowMs) {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomUserNotificationEnabled()).thenReturn(enabled);
        lenient().when(config.wecomUserNotificationWindowMs()).thenReturn(windowMs);
        return config;
    }

    private static WeComUserNotificationService.InboundMessage inbound(
            String contactLabel, String subject, String body) {
        return new WeComUserNotificationService.InboundMessage(
                CONVERSATION, ACCOUNT, "chatapp", contactLabel, subject, body, NOW.minusSeconds(5));
    }

    private static ConversationEntity conversation(UUID assignee) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(CONVERSATION);
        conversation.setAssignedUserId(assignee);
        return conversation;
    }

    private static WeComUserBindingEntity binding(String wecomUserId) {
        WeComUserBindingEntity binding = new WeComUserBindingEntity();
        binding.setUserId(ASSIGNEE);
        binding.setSuiteId("suite-1");
        binding.setAuthCorpId("corp-1");
        binding.setWecomUserId(wecomUserId);
        return binding;
    }

    private static WeComInstallationEntity installation(String agentId) {
        WeComInstallationEntity installation = new WeComInstallationEntity();
        installation.setAgentId(agentId);
        return installation;
    }
}
