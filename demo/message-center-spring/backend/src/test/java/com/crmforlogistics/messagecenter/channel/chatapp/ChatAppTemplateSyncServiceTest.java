package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateReconciliationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatAppTemplateSyncServiceTest {

    @Mock ChannelAccountMapper channelAccountMapper;
    @Mock WhatsAppTemplateReconciliationService reconciliationService;

    @Test
    void shouldConstructWithDependencies() {
        ChatAppTemplateSyncService service = service();
        assertNotNull(service);
    }

    @Test
    void shouldRejectNullDependencies() {
        assertThrows(NullPointerException.class, () ->
                new ChatAppTemplateSyncService(null, channelAccountMapper));
    }

    @Test
    void skipsWhenNoChatAppAccountExists() {
        when(channelAccountMapper.selectList(any())).thenReturn(List.of());

        ChatAppTemplateSyncService.SyncResultRecord result = service().runOnce();

        assertEquals(0, result.pages());
        assertEquals(0, result.fetched());
        assertEquals(0, result.changed());
        verify(reconciliationService, never()).syncAccount(any());
    }

    @Test
    void delegatesAccountSnapshotSyncToReconciliationOwner() {
        UUID channelAccountId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(channelAccountId);
        when(channelAccountMapper.selectList(any())).thenReturn(List.of(account));
        when(reconciliationService.syncAccount(channelAccountId))
                .thenReturn(new WhatsAppTemplateReconciliationService.SyncResult(2, 120, 7, true));

        ChatAppTemplateSyncService.SyncResultRecord result = service().runOnce();

        assertEquals(2, result.pages());
        assertEquals(120, result.fetched());
        assertEquals(7, result.changed());
        verify(reconciliationService).syncAccount(channelAccountId);
    }

    private ChatAppTemplateSyncService service() {
        return new ChatAppTemplateSyncService(reconciliationService, channelAccountMapper);
    }
}
