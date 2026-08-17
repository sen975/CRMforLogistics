package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComChatDataSyncServiceTest {

    @Test
    void systemSyncResolvesConfiguredInstallationAndUsesSystemActor() {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomSuiteId()).thenReturn("suite");
        when(config.wecomLoginAuthCorpId()).thenReturn("wwcorp");
        WeComInstallationService installations = mock(WeComInstallationService.class);
        ResolvedInstallation installation = new ResolvedInstallation(
                "installation", "suite", "wwcorp", "agent", "permanent", 1L);
        when(installations.resolveInstallation("suite", "wwcorp")).thenReturn(installation);
        WeComStartupGate gate = mock(WeComStartupGate.class);
        WeComChatDataSyncService service = spy(new WeComChatDataSyncService(
                config,
                mock(WeComChatDataGateway.class),
                mock(WeComChatDataStore.class),
                mock(ViewerAuditSink.class),
                installations,
                gate));
        WeComChatDataSyncService.SyncResult expected = new WeComChatDataSyncService.SyncResult(1, 2, 0);
        doReturn(expected).when(service).sync(argThat(context ->
                "system:auto-sync".equals(context.wecomUserId())
                        && installation.equals(context.installation())));

        assertThat(service.syncSystem()).isEqualTo(expected);

        verify(gate).requireOpen();
        verify(installations).resolveInstallation("suite", "wwcorp");
    }
}
