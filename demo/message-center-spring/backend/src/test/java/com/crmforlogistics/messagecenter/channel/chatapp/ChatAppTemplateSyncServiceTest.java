package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
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
class ChatAppTemplateSyncServiceTest {

    @Mock AppConfig appConfig;
    @Mock SyncCursorMapper syncCursorMapper;
    @Mock TemplateMapper templateMapper;
    @Mock ChannelAccountMapper channelAccountMapper;

    @Test
    void shouldConstructWithDependencies() {
        ChatAppTemplateSyncService service = new ChatAppTemplateSyncService(appConfig, syncCursorMapper, templateMapper, channelAccountMapper);
        assertNotNull(service);
    }

    @Test
    void shouldRejectNullDependencies() {
        assertThrows(NullPointerException.class, () ->
                new ChatAppTemplateSyncService(null, syncCursorMapper, templateMapper, channelAccountMapper));
    }
}
