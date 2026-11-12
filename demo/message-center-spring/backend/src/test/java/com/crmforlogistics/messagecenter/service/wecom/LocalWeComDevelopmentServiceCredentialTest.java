package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataMessageEntity;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalWeComDevelopmentServiceCredentialTest {

    @Test
    void encryptsFixtureSecretKeysBeforeStartupSeedAndLaterSyncWrites() {
        AppConfig config = mock(AppConfig.class);
        WeComChatDataMessageMapper mapper = mock(WeComChatDataMessageMapper.class);
        WeComCredentialProtector protector = mock(WeComCredentialProtector.class);
        when(config.localDevMode()).thenReturn(true);
        when(config.localWeComDataSource()).thenReturn("fixture");
        when(config.wecomLoginMaxPending()).thenReturn(8);
        when(config.wecomLoginAttemptTtlSeconds()).thenReturn(60);
        when(config.wecomViewerAuthTtlSeconds()).thenReturn(60);
        when(protector.protectSecretKey(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> "encrypted:" + invocation.getArgument(0));

        LocalWeComDevelopmentService service = new LocalWeComDevelopmentService(
                config, new ObjectMapper(), mapper, protector, Clock.systemUTC());
        var attempt = service.createAttempt();
        assertThat(attempt.loginType()).isEqualTo("Local");
        assertThat(attempt.agentId()).isEqualTo("local-agent");
        var login = service.exchange("code", attempt.state());
        service.sync(login.viewerAuthToken());

        ArgumentCaptor<WeComChatDataMessageEntity> captor =
                ArgumentCaptor.forClass(WeComChatDataMessageEntity.class);
        verify(mapper, times(6)).insertIgnore(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(entity -> {
            assertThat(entity.getId()).isNotNull();
            assertThat(entity.getSecretKey()).startsWith("encrypted:");
        });
        verify(protector, times(2)).protectSecretKey("local-secret-001");
    }
}
