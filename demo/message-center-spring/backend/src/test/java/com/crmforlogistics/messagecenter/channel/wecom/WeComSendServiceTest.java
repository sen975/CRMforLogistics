package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.service.wecom.WeComAccessTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeComSendServiceTest {

    @Mock AppConfig config;
    @Mock WeComInstallationService installationService;
    @Mock WeComAccessTokenService accessTokens;

    @Test
    void shouldConstructWithDependencies() {
        var service = new WeComSendService(config, installationService, accessTokens,
                new com.fasterxml.jackson.databind.ObjectMapper());
        assertNotNull(service);
    }

    @Test
    void resolvesDevelopedAppTokenThroughSharedInstallationTokenOwner() {
        ResolvedInstallation installation = new ResolvedInstallation(
                "installation-1", "dk-suite", "ww-corp", "1000001", "developed-secret", 1);
        when(config.wecomSuiteId()).thenReturn("dk-suite");
        when(installationService.resolveInstallation("dk-suite", "ww-corp"))
                .thenReturn(installation);
        when(accessTokens.accessToken(installation)).thenReturn("access-token");
        var service = new WeComSendService(config, installationService, accessTokens,
                new com.fasterxml.jackson.databind.ObjectMapper());

        assertEquals("access-token", service.accessToken("ww-corp"));
        verify(accessTokens).accessToken(installation);
    }

    @Test
    void shouldReturnSendResultRecord() {
        var result = new WeComSendService.SendResult("msg-1", "agent-1",
                "user-1", "hello", "sent");
        assertEquals("msg-1", result.messageId());
        assertEquals("sent", result.status());
    }
}
