package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.mapper.WeComCredentialMigrationMapper;
import com.crmforlogistics.messagecenter.mapper.WeComCredentialRow;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComCredentialMigrationServiceTest {
    private static final UUID INSTALLATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private WeComCredentialMigrationMapper mapper;
    private WeComCredentialProtector protector;
    private WeComCredentialMigrationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(WeComCredentialMigrationMapper.class);
        protector = mock(WeComCredentialProtector.class);
        service = new WeComCredentialMigrationService(mapper, protector);
    }

    @Test
    void migratesAtMostTwoHundredRowsPerBatchAndMarksCompletion() {
        when(mapper.nextInstallations(null, 200))
                .thenReturn(List.of(new WeComCredentialRow(INSTALLATION_ID, "plain")), List.of());
        when(mapper.nextChatDataMessages(null, 200))
                .thenReturn(List.of(new WeComCredentialRow(MESSAGE_ID, "secret")), List.of());
        when(protector.protectPermanentCode("plain")).thenReturn("encrypted-permanent");
        when(protector.protectSecretKey("secret")).thenReturn("encrypted-secret");
        when(mapper.replaceInstallation(INSTALLATION_ID, "plain", "encrypted-permanent")).thenReturn(1);
        when(mapper.replaceChatDataMessage(MESSAGE_ID, "secret", "encrypted-secret")).thenReturn(1);

        service.migrateAll();

        verify(mapper).nextInstallations(null, 200);
        verify(mapper).replaceInstallation(INSTALLATION_ID, "plain", "encrypted-permanent");
        verify(mapper).nextChatDataMessages(null, 200);
        verify(mapper).replaceChatDataMessage(MESSAGE_ID, "secret", "encrypted-secret");
        verify(mapper).insertMarker("wecom-credentials-v1");
    }

    @Test
    void skipsAlreadyEncryptedRowsWithoutReplacingThem() {
        when(mapper.nextInstallations(null, 200))
                .thenReturn(List.of(new WeComCredentialRow(INSTALLATION_ID, "envelope")), List.of());
        when(mapper.nextChatDataMessages(null, 200)).thenReturn(List.of());
        when(protector.isEnvelope("envelope")).thenReturn(true);

        service.migrateAll();

        verify(mapper, never()).replaceInstallation(eq(INSTALLATION_ID), any(), any());
        verify(mapper).insertMarker("wecom-credentials-v1");
    }
}
