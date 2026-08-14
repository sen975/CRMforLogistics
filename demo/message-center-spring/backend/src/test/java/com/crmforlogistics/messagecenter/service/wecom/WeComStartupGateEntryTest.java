package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComCallbackCodec;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataPublicKeyGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComController;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.channel.wecom.WeComSendService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class WeComStartupGateEntryTest {

    @Test
    void rejectsCallbackBeforeMigrationCompletes() {
        WeComCallbackCodec codec = mock(WeComCallbackCodec.class);
        WeComInstallationService installationService = mock(WeComInstallationService.class);
        WeComController controller = new WeComController(codec, installationService,
                mock(WeComSendService.class), new WeComStartupGate());

        var response = controller.callback("signature", "timestamp", "nonce", "body");

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        verifyNoInteractions(codec, installationService);
    }

    @Test
    void preventsChatDataSchedulerFromCallingSyncBeforeMigrationCompletes() {
        AppConfig config = mock(AppConfig.class);
        WeComChatDataSyncService syncService = mock(WeComChatDataSyncService.class);

        new WeComChatDataSyncRuntime(config, mock(WeComInstallationService.class), syncService,
                new WeComStartupGate()).runOnce();

        verifyNoInteractions(syncService);
    }

    @Test
    void preventsDailySummarySchedulerFromRunningBeforeMigrationCompletes() {
        AppConfig config = mock(AppConfig.class);
        WeComDailySummaryService service = mock(WeComDailySummaryService.class);

        new WeComDailySummaryScheduler(config, service,
                Clock.fixed(Instant.parse("2026-01-01T16:00:00Z"), ZoneOffset.UTC),
                new WeComStartupGate()).tick();

        verifyNoInteractions(service);
    }

    @Test
    void preventsPublicKeyRegistrationBeforeMigrationCompletes() {
        AppConfig config = mock(AppConfig.class);
        WeComInstallationService installationService = mock(WeComInstallationService.class);
        WeComAccessTokenService accessTokens = mock(WeComAccessTokenService.class);
        WeComChatDataPublicKeyRegistrationStore registrationStore =
                mock(WeComChatDataPublicKeyRegistrationStore.class);
        WeComChatDataPublicKeyGateway gateway = mock(WeComChatDataPublicKeyGateway.class);

        new WeComChatDataPublicKeyRegistrar(config, installationService, accessTokens,
                registrationStore, gateway, new WeComStartupGate()).registerIfNeeded();

        verifyNoInteractions(config, installationService, accessTokens, registrationStore, gateway);
    }
}
