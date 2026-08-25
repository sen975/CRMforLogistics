package com.crmforlogistics.messagecenter.service.channel;

import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppMessageSyncService;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppTemplateSyncService;
import com.crmforlogistics.messagecenter.channel.email.EmailSyncService;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.wecom.WeComChatDataSyncService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelAccountServiceWeComTest {

    @Test
    void manualWeComSyncUsesSystemChatDataSync() throws Exception {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        account.setChannelType("wecom");
        when(mapper.selectById(accountId)).thenReturn(account);
        WeComChatDataSyncService weComSync = mock(WeComChatDataSyncService.class);
        when(weComSync.syncSystem()).thenReturn(new WeComChatDataSyncService.SyncResult(1, 2, 0));
        ChannelAccountService service = new ChannelAccountService(
                mapper,
                mock(ChatAppMessageSyncService.class),
                mock(ChatAppTemplateSyncService.class),
                mock(EmailSyncService.class),
                mock(CredentialCipher.class),
                weComSync);

        service.sync(accountId);

        verify(weComSync).syncSystem();
        verify(mapper).updateSyncStatus(eq(accountId), eq("success"), any());
    }
}
