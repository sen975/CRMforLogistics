package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void syncPassesTheResolvedInstallationToTheStoreForProfileRefresh() throws Exception {
        Path privateKey = Files.createTempFile("wecom-chatdata", ".pem");
        try {
            AppConfig config = mock(AppConfig.class);
            when(config.wecomChatDataPrivateKeyFile()).thenReturn(privateKey.toString());
            when(config.wecomChatDataProgramId()).thenReturn("program");
            when(config.wecomChatDataAbilityId()).thenReturn("ability");
            when(config.wecomChatDataSyncLimit()).thenReturn(50);
            when(config.wecomChatDataSyncMaxPages()).thenReturn(1);
            when(config.wecomChatDataSyncTimeoutSeconds()).thenReturn(15);
            ResolvedInstallation installation = new ResolvedInstallation(
                    "installation", "suite", "corp", "agent", "permanent", 1L);
            WeComChatDataGateway gateway = mock(WeComChatDataGateway.class);
            when(gateway.sync(eq(installation), eq(""), eq(50), any())).thenReturn(
                    new WeComChatDataGateway.ProgramPage(false, "next", List.of()));
            WeComChatDataStore store = mock(WeComChatDataStore.class);
            when(store.cursor(any())).thenReturn("");
            when(store.publishPage(eq(installation), any(), eq("next"), eq(List.of()))).thenReturn(
                    new WeComChatDataStore.PublishResult(0, 0));
            WeComStartupGate gate = mock(WeComStartupGate.class);
            WeComChatDataSyncService service = new WeComChatDataSyncService(
                    config, gateway, store, mock(ViewerAuditSink.class),
                    mock(WeComInstallationService.class), gate);

            assertThat(service.sync(new ViewerSyncContext("viewer", installation))).isEqualTo(
                    new WeComChatDataSyncService.SyncResult(1, 0, 0));

            verify(store).publishPage(eq(installation), any(), eq("next"), eq(List.of()));
        } finally {
            Files.deleteIfExists(privateKey);
        }
    }
}
