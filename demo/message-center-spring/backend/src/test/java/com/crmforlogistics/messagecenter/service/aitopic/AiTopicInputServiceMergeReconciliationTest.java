package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.channel.wecom.WeComMessageSummaryJobEntity;
import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.CallRecordMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.WeComMessageSummaryJobMapper;
import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiTopicInputServiceMergeReconciliationTest {
    @Test
    void collectsHistoryFromArchivedTopicsOfMergedContact() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        CallRecordMapper calls = mock(CallRecordMapper.class);
        WeComMessageSummaryJobMapper summaries = mock(WeComMessageSummaryJobMapper.class);
        AiTopicInputService input = new AiTopicInputService(conversations, messages, accounts, identities, calls,
                summaries, new AiTopicConfig("", "", "model", 30, 200, 262144, .65, 1, 3, 120, 30));
        UUID target = UUID.randomUUID();

        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setChannelAccountId(UUID.randomUUID());
        message.setOccurredAt(Instant.parse("2026-08-01T00:00:00Z"));
        message.setDirection("inbound");
        message.setBodyText("历史邮件");
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setChannelType("email");
        when(messages.listArchivedMergedContactMessages(target, 200)).thenReturn(List.of(message));
        when(accounts.selectById(message.getChannelAccountId())).thenReturn(account);

        CallRecordEntity call = new CallRecordEntity();
        call.setId(UUID.randomUUID());
        call.setOccurredAt(Instant.parse("2026-08-01T01:00:00Z"));
        call.setDirection("outbound");
        call.setNote("历史电话");
        when(calls.listArchivedMergedContactCalls(target, 200)).thenReturn(List.of(call));

        WeComMessageSummaryJobEntity summary = new WeComMessageSummaryJobEntity();
        summary.setId(UUID.randomUUID());
        summary.setSendTime(1_785_553_200L);
        summary.setSummary("历史企业微信");
        when(summaries.listArchivedMergedContactSummaries(target, 200)).thenReturn(List.of(summary));

        AiTopicModels.InputBatch batch = input.collect(AiTopicOwnerService.contact(target), null, java.util.Optional.empty());

        assertThat(batch.items()).extracting(AiTopicModels.SourceItem::text)
                .containsExactly("历史邮件", "历史电话", "历史企业微信");
    }
}
