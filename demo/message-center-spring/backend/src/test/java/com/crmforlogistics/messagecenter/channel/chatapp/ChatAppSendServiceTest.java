package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.config.AppConfig;
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
class ChatAppSendServiceTest {

    @Mock
    AppConfig appConfig;

    @Test
    void shouldConstructWithAppConfig() {
        ChatAppSendService service = new ChatAppSendService(appConfig);
        assertNotNull(service);
    }

    @Test
    void shouldRejectNullAppConfig() {
        assertThrows(NullPointerException.class, () -> new ChatAppSendService(null));
    }
}
