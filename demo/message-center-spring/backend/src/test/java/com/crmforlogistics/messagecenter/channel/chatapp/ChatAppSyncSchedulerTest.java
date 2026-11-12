package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatAppSyncSchedulerTest {

    @Test
    void messageSyncTracksSuccessAndFailurePerAccount() {
        ChatAppMessageSyncService messageSyncService = mock(ChatAppMessageSyncService.class);
        ChatAppTemplateSyncService templateSyncService = mock(ChatAppTemplateSyncService.class);
        ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
        ChannelAccountEntity successful = account(UUID.randomUUID());
        ChannelAccountEntity failed = account(UUID.randomUUID());
        when(accountMapper.selectActiveChatAppAccountsForSync())
                .thenReturn(List.of(successful, failed));
        when(messageSyncService.runAccount(successful.getId()))
                .thenReturn(new ChatAppMessageSyncService.SyncResultRecord(1, 2, 2, 0, 10));
        when(messageSyncService.runAccount(failed.getId()))
                .thenThrow(new IllegalStateException("CHATAPP_MESSAGE_HISTORY_SYNC_FAILED"));
        ChatAppSyncScheduler scheduler = new ChatAppSyncScheduler(
                messageSyncService, templateSyncService, accountMapper);

        scheduler.syncMessages();

        var ordered = inOrder(accountMapper, messageSyncService);
        ordered.verify(accountMapper).updateSyncStatus(successful.getId(), "syncing", null);
        ordered.verify(messageSyncService).runAccount(successful.getId());
        ordered.verify(accountMapper).updateSyncStatus(
                eq(successful.getId()), eq("success"), eq(null));
        ordered.verify(accountMapper).updateSyncStatus(failed.getId(), "syncing", null);
        ordered.verify(messageSyncService).runAccount(failed.getId());
        ordered.verify(accountMapper).updateSyncStatus(
                eq(failed.getId()), eq("failed"), eq(null));
        verify(accountMapper, never()).updateSyncStatus(
                eq(failed.getId()), eq("success"), any(Instant.class));
    }

    @Test
    void templateSyncRunsForEveryActiveAccount() {
        ChatAppMessageSyncService messageSyncService = mock(ChatAppMessageSyncService.class);
        ChatAppTemplateSyncService templateSyncService = mock(ChatAppTemplateSyncService.class);
        ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
        ChannelAccountEntity first = account(UUID.randomUUID());
        ChannelAccountEntity second = account(UUID.randomUUID());
        when(accountMapper.selectActiveChatAppAccountsForSync()).thenReturn(List.of(first, second));
        when(templateSyncService.runAccount(first.getId()))
                .thenReturn(new ChatAppTemplateSyncService.SyncResultRecord(1, 2, 1, 10));
        when(templateSyncService.runAccount(second.getId()))
                .thenReturn(new ChatAppTemplateSyncService.SyncResultRecord(1, 3, 2, 10));
        ChatAppSyncScheduler scheduler = new ChatAppSyncScheduler(
                messageSyncService, templateSyncService, accountMapper);

        scheduler.syncTemplates();

        verify(templateSyncService).runAccount(first.getId());
        verify(templateSyncService).runAccount(second.getId());
    }

    private static ChannelAccountEntity account(UUID id) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(id);
        account.setChannelType("chatapp");
        account.setAuthStatus("active");
        return account;
    }
}
