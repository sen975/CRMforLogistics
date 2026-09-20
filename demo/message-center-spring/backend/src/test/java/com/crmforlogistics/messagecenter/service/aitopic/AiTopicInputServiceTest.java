package com.crmforlogistics.messagecenter.service.aitopic;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.CallRecordMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.WeComMessageSummaryJobMapper;
import com.crmforlogistics.messagecenter.entity.WeComMessageSummaryJobEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTopicInputServiceTest {
    @Test
    void skipsCallRecordQueryWhenContactHasNoPhoneAnchors() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        CallRecordMapper calls = mock(CallRecordMapper.class);
        AiTopicConfig config = new AiTopicConfig("", "", "model", 30, 200, 262144, .65, 1, 3, 120, 30);
        AiTopicInputService input = new AiTopicInputService(conversations, messages, accounts, identities, calls, config);
        UUID contactId = UUID.randomUUID();

        when(conversations.listThreads(any(), eq(contactId))).thenReturn(new Page<ConversationEntity>());
        when(identities.findByContactId(contactId)).thenReturn(List.of());

        input.collect(contactId, Optional.empty());

        verify(calls, never()).listUnassignedByAnchors(any());
    }

    @Test
    void collectsOnlyUnassignedMessagesFromAccessibleConversations() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        CallRecordMapper calls = mock(CallRecordMapper.class);
        AiTopicConfig config = new AiTopicConfig("", "", "model", 30, 200, 262144, .65, 1, 3, 120, 30);
        AiTopicInputService input = new AiTopicInputService(conversations, messages, accounts, identities, calls, config);

        UUID contactId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        when(conversations.listAccessibleForContact(contactId, userId)).thenReturn(List.of(conversation));
        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setChannelAccountId(accountId);
        message.setOccurredAt(Instant.parse("2026-08-01T00:00:00Z"));
        message.setDirection("inbound");
        message.setSubject("报价");
        message.setBodyText("请报价");
        when(messages.listUnassignedMessagesByConversations(any(), eq(List.of(conversationId)), eq(userId), any(), any(), anyBoolean()))
                .thenReturn(new Page<MessageEntity>().setRecords(List.of(message)));
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setChannelType("email");
        when(accounts.selectById(accountId)).thenReturn(account);
        when(identities.findByContactId(contactId)).thenReturn(List.of());

        AiTopicModels.InputBatch batch = input.collect(contactId, userId, Optional.empty());

        assertThat(batch.items()).extracting(AiTopicModels.SourceItem::channelType).containsExactly("email");
        verify(messages).listUnassignedMessagesByConversations(any(), eq(List.of(conversationId)), eq(userId), any(), any(), eq(true));
    }

    @Test
    void filterSupportedSourcesExcludesWeComAndHashesChronologicalContent() {
        UUID email = UUID.randomUUID();
        UUID wecom = UUID.randomUUID();
        UUID call = UUID.randomUUID();
        List<AiTopicModels.SourceItem> input = List.of(
                new AiTopicModels.SourceItem(call, AiTopicModels.SourceType.CALL_RECORD, "phone",
                        Instant.parse("2026-08-01T02:00:00Z"), "inbound", "", "call text"),
                new AiTopicModels.SourceItem(wecom, AiTopicModels.SourceType.MESSAGE, "wecom",
                        Instant.parse("2026-08-01T01:00:00Z"), "inbound", "", "must not enter"),
                new AiTopicModels.SourceItem(email, AiTopicModels.SourceType.MESSAGE, "email",
                        Instant.parse("2026-08-01T00:00:00Z"), "inbound", "报价", "邮件正文"));

        List<AiTopicModels.SourceItem> filtered = AiTopicInputService.filterSupportedSources(input);
        assertThat(filtered).extracting(AiTopicModels.SourceItem::channelType)
                .containsExactly("email", "phone");
        assertThat(AiTopicInputService.fingerprint(filtered)).hasSize(64);
        assertThat(AiTopicInputService.fingerprint(filtered))
                .isEqualTo(AiTopicInputService.fingerprint(List.copyOf(filtered)));
    }

    @Test
    void includesCompletedDirectWecomSummaryAsDedicatedSourceType() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        CallRecordMapper calls = mock(CallRecordMapper.class);
        WeComMessageSummaryJobMapper summaries = mock(WeComMessageSummaryJobMapper.class);
        AiTopicConfig config = new AiTopicConfig("", "", "model", 30, 200, 262144, .65, 1, 3, 120, 30);
        AiTopicInputService input = new AiTopicInputService(conversations, messages, accounts, identities, calls,
                summaries, config);
        UUID contactId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        WeComMessageSummaryJobEntity summary = new WeComMessageSummaryJobEntity();
        summary.setId(UUID.randomUUID());
        summary.setSendTime(1_787_891_200L);
        summary.setSummary("客户确认九月出货计划");
        when(conversations.listAccessibleForContact(contactId, userId)).thenReturn(List.of());
        when(identities.findByContactId(contactId)).thenReturn(List.of());
        when(summaries.listCompletedUnassignedForContact(contactId, 200)).thenReturn(List.of(summary));

        AiTopicModels.InputBatch batch = input.collect(contactId, userId, Optional.empty());

        assertThat(batch.items()).singleElement().satisfies(item -> {
            assertThat(item.sourceType()).isEqualTo(AiTopicModels.SourceType.WECOM_SUMMARY);
            assertThat(item.channelType()).isEqualTo("wecom");
            assertThat(item.text()).isEqualTo("客户确认九月出货计划");
        });
    }

    @Test
    void fingerprintChangesWhenOfficialSummaryTextChanges() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-01T00:00:00Z");
        var first = new AiTopicModels.SourceItem(id, AiTopicModels.SourceType.WECOM_SUMMARY, "wecom", at,
                "inbound", "", "第一版摘要");
        var second = new AiTopicModels.SourceItem(id, AiTopicModels.SourceType.WECOM_SUMMARY, "wecom", at,
                "inbound", "", "第二版摘要");
        assertThat(AiTopicInputService.fingerprint(List.of(first)))
                .isNotEqualTo(AiTopicInputService.fingerprint(List.of(second)));
    }

    @Test
    void ownerScopedFingerprintChangesForDifferentOwnersAndSummaryJobs() {
        Instant at = Instant.parse("2026-09-01T00:00:00Z");
        var firstJob = new AiTopicModels.SourceItem(UUID.randomUUID(), AiTopicModels.SourceType.WECOM_SUMMARY,
                "wecom", at, "inbound", "", "客户确认九月出货计划");
        var secondJob = new AiTopicModels.SourceItem(UUID.randomUUID(), AiTopicModels.SourceType.WECOM_SUMMARY,
                "wecom", at, "inbound", "", "客户确认九月出货计划");
        var firstOwner = AiTopicOwnerService.contact(UUID.randomUUID());
        var secondOwner = AiTopicOwnerService.contact(UUID.randomUUID());

        assertThat(AiTopicInputService.fingerprint(firstOwner, List.of(firstJob)))
                .isNotEqualTo(AiTopicInputService.fingerprint(secondOwner, List.of(firstJob)))
                .isNotEqualTo(AiTopicInputService.fingerprint(firstOwner, List.of(secondJob)));
    }

    @Test
    void groupOwnerCollectsOnlyThatGroupsCompletedSummaries() {
        WeComMessageSummaryJobMapper summaries = mock(WeComMessageSummaryJobMapper.class);
        AiTopicInputService input = new AiTopicInputService(mock(ConversationMapper.class), mock(MessageMapper.class),
                mock(ChannelAccountMapper.class), mock(ContactIdentityMapper.class), mock(CallRecordMapper.class),
                summaries, new AiTopicConfig("", "", "model", 30, 200, 262144, .65, 1, 3, 120, 30));
        UUID groupId = UUID.randomUUID();
        WeComMessageSummaryJobEntity summary = new WeComMessageSummaryJobEntity();
        summary.setId(UUID.randomUUID());
        summary.setSendTime(1_787_891_200L);
        summary.setSummary("群内确认装柜时间");
        when(summaries.listCompletedUnassignedForGroup(groupId, 200)).thenReturn(List.of(summary));

        AiTopicModels.InputBatch batch = input.collect(AiTopicOwnerService.group(groupId), UUID.randomUUID(), Optional.empty());

        assertThat(batch.items()).singleElement().satisfies(item -> {
            assertThat(item.sourceType()).isEqualTo(AiTopicModels.SourceType.WECOM_SUMMARY);
            assertThat(item.text()).isEqualTo("群内确认装柜时间");
        });
    }

    @Test
    void fingerprintSeparatesDelimiterCharactersInsideMessageFields() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-08-01T00:00:00Z");
        var withDelimiter = new AiTopicModels.SourceItem(id, AiTopicModels.SourceType.MESSAGE, "email", at,
                "in|bound", "subject", "body");
        var withoutDelimiter = new AiTopicModels.SourceItem(id, AiTopicModels.SourceType.MESSAGE, "email", at,
                "in", "bound|subject", "body");
        assertThat(AiTopicInputService.fingerprint(List.of(withDelimiter)))
                .isNotEqualTo(AiTopicInputService.fingerprint(List.of(withoutDelimiter)));
    }
}
