package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailSyncSchedulerOwnerTest {

    @Mock EmailSyncService syncService;
    @Mock ChannelAccountMapper channelAccountMapper;

    @Test
    void schedulerSyncsEveryOwnedEmailAccountAndContinuesAfterFailure() throws Exception {
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        ChannelAccountEntity accountA = account(ownerA);
        ChannelAccountEntity accountB = account(ownerB);
        when(channelAccountMapper.selectList(any())).thenReturn(List.of(accountA, accountB));
        doThrow(new EmailException("EMAIL_SYNC_FAILED", "failed"))
                .when(syncService).receiveLatest(accountA.getId(), ownerA);
        when(syncService.receiveLatest(accountB.getId(), ownerB))
                .thenReturn(new EmailSyncService.SyncResult("email", 1, 1, 0, "ok"));

        new EmailSyncScheduler(syncService, channelAccountMapper).syncEmail();

        verify(syncService).receiveLatest(accountA.getId(), ownerA);
        verify(syncService).receiveLatest(accountB.getId(), ownerB);
        verify(channelAccountMapper).updateSyncStatusOwned(eq(ownerA), eq(accountA.getId()), eq("failed"), any());
        verify(channelAccountMapper).updateSyncStatusOwned(eq(ownerB), eq(accountB.getId()), eq("success"), any());
    }

    private static ChannelAccountEntity account(UUID ownerId) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setOwnerUserId(ownerId);
        account.setChannelType("email");
        account.setAuthStatus("active");
        return account;
    }
}
