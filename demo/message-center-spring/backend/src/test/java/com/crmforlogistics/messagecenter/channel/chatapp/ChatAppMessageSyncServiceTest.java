package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatAppMessageSyncServiceTest {

    @Mock AppConfig appConfig;
    @Mock SyncCursorMapper syncCursorMapper;
    @Mock ChannelAccountMapper channelAccountMapper;

    @Test
    void shouldConstructWithDependencies() {
        ChatAppMessageSyncService service = new ChatAppMessageSyncService(appConfig, syncCursorMapper, channelAccountMapper);
        assertNotNull(service);
    }

    @Test
    void shouldRejectNullDependencies() {
        assertThrows(NullPointerException.class, () -> new ChatAppMessageSyncService(null, syncCursorMapper, channelAccountMapper));
        assertThrows(NullPointerException.class, () -> new ChatAppMessageSyncService(appConfig, null, channelAccountMapper));
    }
}
