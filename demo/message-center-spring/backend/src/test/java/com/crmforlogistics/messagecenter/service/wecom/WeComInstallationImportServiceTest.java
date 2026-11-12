package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.mapper.WeComInstallationMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecentertest.wecom.WeComInstallationImportTransactionTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WeComInstallationImportServiceTest {
    private static final String SUITE_ID = "suite-1";
    private static final String CORP_ID = "ww-corp";
    private static final String AGENT_ID = "1000247";
    private static final String PERMANENT_CODE = "permanent-code";

    private final WeComInstallationMapper mapper = mock(WeComInstallationMapper.class);
    private final WeComCredentialProtector protector = mock(WeComCredentialProtector.class);
    private final WeComInstallationImportService service =
            new WeComInstallationImportService(mapper, protector);

    @Test
    void springCanCreateTransactionalImportService() {
        new ApplicationContextRunner()
                .withBean(WeComInstallationMapper.class, () -> mock(WeComInstallationMapper.class))
                .withBean(WeComCredentialProtector.class, () -> mock(WeComCredentialProtector.class))
                .withBean(ChannelAccountMapper.class, () -> mock(ChannelAccountMapper.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withUserConfiguration(WeComInstallationImportTransactionTestConfiguration.class,
                        WeComChannelAccountLifecycle.class,
                        WeComInstallationImportService.class)
                .withPropertyValues("app.wecom-enabled=true", "app.wecom-suite-id=suite-1")
                .run(context -> assertThat(context).hasSingleBean(WeComInstallationImportService.class));
    }

    @Test
    void rejectsMissingRequiredPropertyWithoutCallingPersistence() throws Exception {
        Path file = properties("authCorpId=ww-corp\nagentId=1000247\n");

        assertThatThrownBy(() -> service.importFile(file, SUITE_ID))
                .isInstanceOf(WeComException.class)
                .extracting("code")
                .isEqualTo("WECOM_INSTALLATION_IMPORT_INVALID");
        verifyNoInteractions(mapper, protector);
    }

    @Test
    void rejectsOversizedPropertiesFileWithoutReadingCredentials() throws Exception {
        Path file = Files.createTempFile("wecom-install", ".properties");
        Files.writeString(file, "x=" + "a".repeat(16 * 1024));

        assertThatThrownBy(() -> service.importFile(file, SUITE_ID))
                .isInstanceOf(WeComException.class)
                .extracting("code")
                .isEqualTo("WECOM_INSTALLATION_IMPORT_INVALID");
        verifyNoInteractions(mapper, protector);
    }

    @Test
    void importsTrimmedValuesAndDelegatesEncryptedPermanentCode() throws Exception {
        Path file = properties("authCorpId=  ww-corp  \nagentId= 1000247 \npermanentCode= permanent-code \n");
        when(mapper.findBySuiteAndAuthCorpId(SUITE_ID, CORP_ID)).thenReturn(null);
        when(protector.protectPermanentCode(PERMANENT_CODE)).thenReturn("encrypted-envelope");
        when(mapper.insert((WeComInstallationEntity) any())).thenReturn(1);

        var result = service.importFile(file, SUITE_ID);

        assertThat(result.action()).isEqualTo("created");
        assertThat(result.suiteId()).isEqualTo(SUITE_ID);
        assertThat(result.authCorpId()).isEqualTo(CORP_ID);
        assertThat(result.agentId()).isEqualTo(AGENT_ID);
        assertThat(result.version()).isEqualTo(1L);
        var entity = org.mockito.ArgumentCaptor.forClass(WeComInstallationEntity.class);
        verify(mapper).insert((WeComInstallationEntity) entity.capture());
        assertThat(entity.getValue().getPermanentCode()).isEqualTo("encrypted-envelope");
        assertThat(entity.getValue().getPermanentCode()).doesNotContain(PERMANENT_CODE);
        assertThat(entity.getValue().getAuthStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void returnsUnchangedWhenExistingPlaintextMatches() throws Exception {
        Path file = properties("authCorpId=ww-corp\nagentId=1000247\npermanentCode=permanent-code\n");
        WeComInstallationEntity existing = existing("encrypted-old", "ACTIVE", 4L);
        when(mapper.findBySuiteAndAuthCorpId(SUITE_ID, CORP_ID)).thenReturn(existing);
        when(protector.isEnvelope("encrypted-old")).thenReturn(true);
        when(protector.revealPermanentCode("encrypted-old")).thenReturn(PERMANENT_CODE);

        var result = service.importFile(file, SUITE_ID);

        assertThat(result.action()).isEqualTo("unchanged");
        assertThat(result.installationId()).isEqualTo(existing.getId().toString());
        assertThat(result.version()).isEqualTo(4L);
        verify(mapper, never()).insert((WeComInstallationEntity) any());
        verify(mapper, never()).updateImported(any(), any(), any(), any(), any(), anyLong());
        verify(protector, never()).protectPermanentCode(any());
    }

    @Test
    void updatesChangedCredentialWithoutChangingInstallationId() throws Exception {
        Path file = properties("authCorpId=ww-corp\nagentId=1000248\npermanentCode=new-code\n");
        WeComInstallationEntity existing = existing("encrypted-old", "ACTIVE", 4L);
        when(mapper.findBySuiteAndAuthCorpId(SUITE_ID, CORP_ID)).thenReturn(existing);
        when(protector.isEnvelope("encrypted-old")).thenReturn(true);
        when(protector.revealPermanentCode("encrypted-old")).thenReturn("old-code");
        when(protector.protectPermanentCode("new-code")).thenReturn("encrypted-new");
        when(mapper.updateImported(eq(existing.getId()), eq("1000248"), eq("encrypted-new"),
                eq("ACTIVE"), any(Instant.class), eq(4L))).thenReturn(1);

        var result = service.importFile(file, SUITE_ID);

        assertThat(result.action()).isEqualTo("updated");
        assertThat(result.installationId()).isEqualTo(existing.getId().toString());
        assertThat(result.version()).isEqualTo(5L);
        verify(mapper).updateImported(eq(existing.getId()), eq("1000248"), eq("encrypted-new"),
                eq("ACTIVE"), any(Instant.class), eq(4L));
    }

    @Test
    void reactivatesRevokedInstallation() throws Exception {
        Path file = properties("authCorpId=ww-corp\nagentId=1000247\npermanentCode=permanent-code\n");
        WeComInstallationEntity existing = existing("encrypted-old", "REVOKED", 4L);
        when(mapper.findBySuiteAndAuthCorpId(SUITE_ID, CORP_ID)).thenReturn(existing);
        when(protector.isEnvelope("encrypted-old")).thenReturn(true);
        when(protector.revealPermanentCode("encrypted-old")).thenReturn(PERMANENT_CODE);
        when(mapper.updateImported(eq(existing.getId()), eq(AGENT_ID), eq("encrypted-old"),
                eq("ACTIVE"), any(Instant.class), eq(4L))).thenReturn(1);

        var result = service.importFile(file, SUITE_ID);

        assertThat(result.action()).isEqualTo("updated");
        assertThat(result.version()).isEqualTo(5L);
    }

    @Test
    void reactivatesADeletedInstallationEvenWhenValuesMatch() throws Exception {
        Path file = properties("authCorpId=ww-corp\nagentId=1000247\npermanentCode=permanent-code\n");
        WeComInstallationEntity existing = existing("encrypted-old", "ACTIVE", 4L);
        existing.setDeletedAt(Instant.parse("2026-08-02T00:00:00Z"));
        when(mapper.findBySuiteAndAuthCorpId(SUITE_ID, CORP_ID)).thenReturn(existing);
        when(protector.isEnvelope("encrypted-old")).thenReturn(true);
        when(protector.revealPermanentCode("encrypted-old")).thenReturn(PERMANENT_CODE);
        when(mapper.updateImported(eq(existing.getId()), eq(AGENT_ID), eq("encrypted-old"),
                eq("ACTIVE"), any(Instant.class), eq(4L))).thenReturn(1);

        var result = service.importFile(file, SUITE_ID);

        assertThat(result.action()).isEqualTo("updated");
        assertThat(result.version()).isEqualTo(5L);
    }

    private static Path properties(String content) throws Exception {
        Path file = Files.createTempFile("wecom-install", ".properties");
        Files.writeString(file, content);
        return file;
    }

    private static WeComInstallationEntity existing(String encryptedCode, String status, long version) {
        WeComInstallationEntity entity = new WeComInstallationEntity();
        entity.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        entity.setSuiteId(SUITE_ID);
        entity.setAuthCorpId(CORP_ID);
        entity.setAgentId(AGENT_ID);
        entity.setPermanentCode(encryptedCode);
        entity.setAuthStatus(status);
        entity.setVersion(version);
        entity.setAuthorizedAt(Instant.parse("2026-08-01T00:00:00Z"));
        return entity;
    }

}
