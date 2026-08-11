package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeComSendServiceTest {

    @Mock AppConfig config;
    @Mock WeComInstallationService installationService;

    @Test
    void shouldConstructWithDependencies() {
        var service = new WeComSendService(config, installationService,
                new com.fasterxml.jackson.databind.ObjectMapper());
        assertNotNull(service);
    }

    @Test
    void shouldReturnSendResultRecord() {
        var result = new WeComSendService.SendResult("msg-1", "agent-1",
                "user-1", "hello", "sent");
        assertEquals("msg-1", result.messageId());
        assertEquals("sent", result.status());
    }
}
