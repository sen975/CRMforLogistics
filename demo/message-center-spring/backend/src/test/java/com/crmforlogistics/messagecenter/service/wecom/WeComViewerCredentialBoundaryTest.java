package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataMessageEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.channel.wecom.WeComViewerHttpGateway;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComViewerCredentialBoundaryTest {

    @Test
    void revealsSecretKeyAtViewerBoundaryInsteadOfReturningStoredEnvelope() {
        AppConfig config = viewerConfig();
        WeComViewerHttpGateway viewerGateway = mock(WeComViewerHttpGateway.class);
        WeComChatDataMessageMapper mapper = mock(WeComChatDataMessageMapper.class);
        WeComCredentialProtector protector = mock(WeComCredentialProtector.class);
        WeComStartupGate gate = new WeComStartupGate();
        gate.open();
        when(viewerGateway.exchangeLoginCode("login-code")).thenReturn("wecom-user");
        WeComChatDataMessageEntity entity = new WeComChatDataMessageEntity();
        entity.setMsgid("message-1");
        entity.setExternalUserid("contact-1");
        entity.setUserid("wecom-user");
        entity.setSendTime(1L);
        entity.setSecretKey("encrypted-envelope");
        when(mapper.findByExternalUserid("contact-1")).thenReturn(List.of(entity));
        when(protector.revealSecretKey("encrypted-envelope")).thenReturn("plain-secret");
        WeComViewerService service = WeComViewerService.forTests(config,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "nonce",
                viewerGateway, mock(WeComInstallationService.class), noOpAudit(), mapper,
                protector, gate);

        String token = service.exchangeLoginCode("login-code").viewerAuthToken();
        String sessionId = service.createViewerSession("wecom:contact-1", token, List.of("message-1"))
                .viewerSessionId();

        assertThat(service.viewerSession(sessionId, token).messages())
                .extracting(WeComViewerService.ViewerMessage::secretKey)
                .containsExactly("plain-secret");
        verify(protector).revealSecretKey("encrypted-envelope");
    }

    @Test
    void rejectsViewerEntryWhenMigrationGateIsClosed() {
        WeComViewerHttpGateway viewerGateway = mock(WeComViewerHttpGateway.class);
        WeComViewerService service = WeComViewerService.forTests(viewerConfig(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "nonce",
                viewerGateway, mock(WeComInstallationService.class), noOpAudit(),
                mock(WeComChatDataMessageMapper.class), mock(WeComCredentialProtector.class),
                new WeComStartupGate());

        assertThatThrownBy(() -> service.exchangeLoginCode("login-code"))
                .hasMessage("企业微信凭据迁移尚未完成");
    }

    private static AppConfig viewerConfig() {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomViewerAuthTtlSeconds()).thenReturn(60);
        when(config.wecomViewerSessionTtlSeconds()).thenReturn(60);
        when(config.wecomViewerMaxMessages()).thenReturn(10);
        when(config.wecomViewerSessionRateLimit()).thenReturn(10);
        return config;
    }

    private static ViewerAuditSink noOpAudit() {
        return new ViewerAuditSink() {
            @Override public void record(String action, String result, String userId,
                                         String contactPointId, String sessionId) { }
            @Override public void recordDiagnostic(String action, String result, String userId,
                                                   String contactPointId, String sessionId,
                                                   String errorCode, Integer upstreamErrcode,
                                                   String upstreamPath, Integer upstreamHttpStatus,
                                                   String upstreamHint) { }
        };
    }
}
