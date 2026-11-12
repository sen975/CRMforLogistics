package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminWhatsAppAccountSyncServiceTest {
    private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SCOPE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID EXISTING_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID MISSING_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID OWNER = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final UUID SCOPE_A = UUID.fromString("a0000000-0000-0000-0000-00000000000a");
    private static final UUID SCOPE_B = UUID.fromString("b0000000-0000-0000-0000-00000000000b");
    private static final UUID FOREIGN_ID = UUID.fromString("60000000-0000-0000-0000-000000000006");

    @Test
    void importsAnActivePhoneWhoseCodeVerificationExpired() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        ChannelAccountEntity existing = account(EXISTING_ID, OWNER, "6013266259485", "disabled", "UNKNOWN");
        WhatsAppProviderScopeEntity scope = scope();
        when(scopes.upsertAdminConfigured(eq("ALIYUN_CAMS"), eq("space-1"), eq("encrypted-config"))).thenReturn(1);
        when(scopes.findByProviderAndExternalScopeId("ALIYUN_CAMS", "space-1")).thenReturn(scope);
        when(accounts.findAllWhatsAppForSync()).thenReturn(List.of(existing));
        when(gateway.encryptedProviderConfig()).thenReturn("encrypted-config");
        when(gateway.encryptedProviderConfig(anyString())).thenReturn("phone-encrypted-config");
        when(gateway.syncConfiguredPhoneNumbers()).thenReturn(List.of(
                new WhatsAppOnboardingGateway.ProviderPhone("+60 1326 625 9485", "小森", "ACTIVE", "EXPIRED")));
        when(accounts.findAllWhatsAppByScope(SCOPE_ID)).thenReturn(List.of(existing));

        AdminWhatsAppAccountSyncService.SyncResult result = service(config(), gateway, scopes, accounts).sync(ACTOR);

        assertThat(result.refreshedCount()).isEqualTo(1);
        assertThat(result.providerPhones()).singleElement()
                .satisfies(report -> assertThat(report.accepted()).isTrue());
        // EXPIRED has no column value, so the stored verification is null rather than a claim the
        // provider never made; the raw value stays in the report above.
        verify(accounts).refreshAdminSynced(eq(EXISTING_ID), eq(SCOPE_ID), eq("小森"), eq("6013266259485"),
                eq("6013266259485"), isNull(), eq("ACTIVE"), eq("phone-encrypted-config"), any(Instant.class));
    }

    @Test
    void importsOnlyActivePhonesAndPreservesExistingIdentityAndOwner() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        AppConfig config = config();
        WhatsAppProviderScopeEntity scope = scope();
        ChannelAccountEntity existing = account(EXISTING_ID, OWNER, "60111111111", "active", "ACTIVE");
        when(scopes.upsertAdminConfigured(eq("ALIYUN_CAMS"), eq("space-1"), eq("encrypted-config"))).thenReturn(1);
        when(scopes.findByProviderAndExternalScopeId("ALIYUN_CAMS", "space-1")).thenReturn(scope);
        when(accounts.findAllWhatsAppForSync()).thenReturn(List.of(existing));
        when(gateway.syncConfiguredPhoneNumbers()).thenReturn(List.of(
                new WhatsAppOnboardingGateway.ProviderPhone("+60 1111 11111", "CRM Test", "ACTIVE", "VERIFIED"),
                new WhatsAppOnboardingGateway.ProviderPhone("+60 2222 22222", "Pending Test", "PENDING", "PENDING")));
        when(gateway.encryptedProviderConfig()).thenReturn("encrypted-config");
        when(gateway.encryptedProviderConfig(anyString())).thenReturn("encrypted-config");
        when(accounts.upsertAdminSynced(any(), eq(SCOPE_ID), any(Instant.class))).thenReturn(1);
        when(accounts.findAllWhatsAppByScope(SCOPE_ID)).thenReturn(List.of(existing));

        AdminWhatsAppAccountSyncService service = service(config, gateway, scopes, accounts);
        AdminWhatsAppAccountSyncService.SyncResult result = service.sync(ACTOR);

        assertThat(result.importedCount()).isEqualTo(0);
        assertThat(result.refreshedCount()).isEqualTo(1);
        assertThat(result.unavailableCount()).isEqualTo(0);
        assertThat(result.accounts()).singleElement().satisfies(projection -> {
            assertThat(projection.accountId()).isEqualTo(EXISTING_ID);
            assertThat(projection.ownerUserId()).isEqualTo(OWNER);
            assertThat(projection.maskedPhone()).isEqualTo("*******1111");
            assertThat(projection.toString()).doesNotContain("60111111111").doesNotContain("encrypted-config");
        });
        verify(accounts).refreshAdminSynced(eq(EXISTING_ID), eq(SCOPE_ID), eq("CRM Test"),
                eq("60111111111"), eq("60111111111"), eq("VERIFIED"), eq("ACTIVE"),
                eq("encrypted-config"), any(Instant.class));
        verify(accounts, never()).insertOwned(any(), any());
    }

    @Test
    void marksMissingLocalPhonesUnavailableWithoutClearingOwnerOrCredentials() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        ChannelAccountEntity missing = account(MISSING_ID, OWNER, "60199999999", "active", "ACTIVE");
        WhatsAppProviderScopeEntity scope = scope();
        when(scopes.upsertAdminConfigured(eq("ALIYUN_CAMS"), eq("space-1"), eq("encrypted-config"))).thenReturn(1);
        when(scopes.findByProviderAndExternalScopeId("ALIYUN_CAMS", "space-1")).thenReturn(scope);
        when(accounts.findAllWhatsAppForSync()).thenReturn(List.of(missing));
        when(gateway.syncConfiguredPhoneNumbers()).thenReturn(List.of());
        when(gateway.encryptedProviderConfig()).thenReturn("encrypted-config");
        when(accounts.markWhatsAppProviderUnavailable(eq(SCOPE_ID), any())).thenReturn(1);
        when(accounts.findAllWhatsAppByScope(SCOPE_ID)).thenReturn(List.of(unavailable(missing)));

        AdminWhatsAppAccountSyncService.SyncResult result = service(config(), gateway, scopes, accounts).sync(ACTOR);

        assertThat(result.importedCount()).isZero();
        assertThat(result.refreshedCount()).isZero();
        assertThat(result.unavailableCount()).isEqualTo(1);
        assertThat(result.accounts()).singleElement().satisfies(projection -> {
            assertThat(projection.accountId()).isEqualTo(MISSING_ID);
            assertThat(projection.ownerUserId()).isEqualTo(OWNER);
            assertThat(projection.providerStatus()).isEqualTo("UNKNOWN");
            assertThat(projection.maskedPhone()).isEqualTo("*******9999");
        });
        verify(accounts).markWhatsAppProviderUnavailable(eq(SCOPE_ID), any(Instant.class));
    }

    @Test
    void reportsTheNumberCamsAnsweredEvenWhenTheSpaceHasNoLocalRowToShow() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppProviderScopeEntity scope = scope();
        when(scopes.upsertAdminConfigured(eq("ALIYUN_CAMS"), eq("space-1"), eq("encrypted-config"))).thenReturn(1);
        when(scopes.findByProviderAndExternalScopeId("ALIYUN_CAMS", "space-1")).thenReturn(scope);
        when(accounts.findAllWhatsAppForSync()).thenReturn(List.of());
        when(gateway.encryptedProviderConfig()).thenReturn("encrypted-config");
        when(gateway.syncConfiguredPhoneNumbers()).thenReturn(List.of(
                new WhatsAppOnboardingGateway.ProviderPhone("+86 132 6625 9485", "小森", "PENDING", "NOT_VERIFIED")));
        when(accounts.findAllWhatsAppByScope(SCOPE_ID)).thenReturn(List.of());

        AdminWhatsAppAccountSyncService.SyncResult result = service(config(), gateway, scopes, accounts).sync(ACTOR);

        assertThat(result.importedCount()).isZero();
        assertThat(result.refreshedCount()).isZero();
        assertThat(result.providerPhones()).singleElement().satisfies(report -> {
            assertThat(report.maskedPhone()).isEqualTo("*********9485");
            assertThat(report.providerStatus()).isEqualTo("PENDING");
            assertThat(report.verificationStatus()).isEqualTo("NOT_VERIFIED");
            assertThat(report.accepted()).isFalse();
        });
        verify(accounts, never()).recordProviderPhoneStatus(any(), any(), any(), any(), any(Instant.class));
    }

    @Test
    void keepsCamsAnswerOnRowsTheProviderReportedButTheSyncWouldNotImport() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        ChannelAccountEntity pending = account(EXISTING_ID, OWNER, "60222222222", "active", "ACTIVE");
        ChannelAccountEntity migrating = account(MISSING_ID, OWNER, "60333333333", "active", "ACTIVE");
        WhatsAppProviderScopeEntity scope = scope();
        when(scopes.upsertAdminConfigured(eq("ALIYUN_CAMS"), eq("space-1"), eq("encrypted-config"))).thenReturn(1);
        when(scopes.findByProviderAndExternalScopeId("ALIYUN_CAMS", "space-1")).thenReturn(scope);
        when(accounts.findAllWhatsAppForSync()).thenReturn(List.of(pending, migrating));
        when(gateway.encryptedProviderConfig()).thenReturn("encrypted-config");
        when(gateway.syncConfiguredPhoneNumbers()).thenReturn(List.of(
                new WhatsAppOnboardingGateway.ProviderPhone("+60 2222 22222", "Pending", "PENDING", "NOT_VERIFIED"),
                new WhatsAppOnboardingGateway.ProviderPhone("+60 3333 33333", "Migrating", "MIGRATING", "VERIFIED")));
        when(accounts.markWhatsAppProviderUnavailable(eq(SCOPE_ID), any())).thenReturn(2);
        when(accounts.findAllWhatsAppByScope(SCOPE_ID)).thenReturn(List.of(pending, migrating));

        AdminWhatsAppAccountSyncService.SyncResult result = service(config(), gateway, scopes, accounts).sync(ACTOR);

        assertThat(result.importedCount()).isZero();
        assertThat(result.refreshedCount()).isZero();
        assertThat(result.providerPhones()).hasSize(2).allSatisfy(report -> assertThat(report.accepted()).isFalse());
        // PENDING is a status the column accepts; NOT_VERIFIED is not, so the stored verification stays.
        verify(accounts).recordProviderPhoneStatus(eq(SCOPE_ID), eq("60222222222"), eq("PENDING"), isNull(),
                any(Instant.class));
        // MIGRATING is not a status the column accepts either, so it is stored as UNKNOWN while the
        // report above still carries the provider's own word.
        verify(accounts).recordProviderPhoneStatus(eq(SCOPE_ID), eq("60333333333"), eq("UNKNOWN"), eq("VERIFIED"),
                any(Instant.class));
        verify(accounts, never()).upsertAdminSynced(any(), any(), any(Instant.class));
        verify(accounts, never()).refreshAdminSynced(any(), any(), any(), any(), any(), any(), any(), any(),
                any(Instant.class));
    }

    @Test
    void importsNewVerifiedPhoneAsUnassignedAdminApiAccount() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppProviderScopeEntity scope = scope();
        when(scopes.upsertAdminConfigured(eq("ALIYUN_CAMS"), eq("space-1"), eq("encrypted-config"))).thenReturn(1);
        when(scopes.findByProviderAndExternalScopeId("ALIYUN_CAMS", "space-1")).thenReturn(scope);
        when(accounts.findAllWhatsAppForSync()).thenReturn(List.of());
        when(gateway.encryptedProviderConfig()).thenReturn("encrypted-config");
        when(gateway.encryptedProviderConfig(anyString())).thenReturn("phone-encrypted-config");
        when(gateway.syncConfiguredPhoneNumbers()).thenReturn(List.of(
                new WhatsAppOnboardingGateway.ProviderPhone("+60 3333 33333", "New CRM", "ACTIVE", "VERIFIED")));
        when(accounts.findAllWhatsAppByScope(SCOPE_ID)).thenReturn(List.of(account(
                UUID.randomUUID(), null, "60333333333", "active", "ACTIVE")));

        AdminWhatsAppAccountSyncService.SyncResult result =
                service(config(), gateway, scopes, accounts).sync(ACTOR);

        assertThat(result.importedCount()).isEqualTo(1);
        assertThat(result.refreshedCount()).isZero();
        assertThat(result.unavailableCount()).isZero();
        assertThat(result.accounts()).singleElement().satisfies(projection -> {
            assertThat(projection.ownerUserId()).isNull();
            assertThat(projection.maskedPhone()).isEqualTo("*******3333");
            assertThat(projection.toString()).doesNotContain("60333333333")
                    .doesNotContain("phone-encrypted-config");
        });
        org.mockito.ArgumentCaptor<ChannelAccountEntity> captured =
                org.mockito.ArgumentCaptor.forClass(ChannelAccountEntity.class);
        verify(accounts).upsertAdminSynced(captured.capture(), eq(SCOPE_ID), any(Instant.class));
        assertThat(captured.getValue().getOwnerUserId()).isNull();
        assertThat(captured.getValue().getAccountIdentifier()).isEqualTo("60333333333");
        assertThat(captured.getValue().getEncryptedConfig()).isEqualTo("phone-encrypted-config");
    }

    @Test
    void syncUsesRequestedScopeConfigAndNeverImportsAnotherScope() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppCamsConfigService camsConfig = mock(WhatsAppCamsConfigService.class);
        RoleMapper roles = adminRoles();
        WhatsAppProviderScopeEntity scopeA = scope(SCOPE_A, "space-a");
        ChannelAccountEntity existingInScopeA = account(EXISTING_ID, OWNER, "60111111111", "active", "ACTIVE");
        existingInScopeA.setProviderScopeId(SCOPE_A);
        ChannelAccountEntity foreignInScopeB = account(FOREIGN_ID, OWNER, "60222222222", "active", "ACTIVE");
        foreignInScopeB.setProviderScopeId(SCOPE_B);
        when(camsConfig.requireReadyScope(SCOPE_A)).thenReturn(scopeA);
        when(accounts.findAllWhatsAppByScope(SCOPE_A)).thenReturn(List.of(existingInScopeA));
        when(accounts.findAllWhatsAppForSync()).thenReturn(List.of(foreignInScopeB));
        when(gateway.syncConfiguredPhoneNumbers(scopeA)).thenReturn(List.of(
                new WhatsAppOnboardingGateway.ProviderPhone("+60 1111 11111", "CRM Test", "ACTIVE", "VERIFIED"),
                new WhatsAppOnboardingGateway.ProviderPhone("+60 2222 22222", "Foreign", "ACTIVE", "VERIFIED"),
                new WhatsAppOnboardingGateway.ProviderPhone("+60 3333 33333", "New CRM", "ACTIVE", "VERIFIED")));
        when(gateway.encryptedProviderConfig(anyString())).thenReturn("encrypted-config");

        AdminWhatsAppAccountSyncService.SyncResult result = scopedService(
                config(), gateway, scopes, accounts, roles, camsConfig).sync(ACTOR, SCOPE_A);

        assertThat(result.importedCount()).isEqualTo(2);
        assertThat(result.refreshedCount()).isEqualTo(1);
        verify(camsConfig).requireReadyScope(SCOPE_A);
        verify(gateway).syncConfiguredPhoneNumbers(scopeA);
        verify(gateway, never()).syncConfiguredPhoneNumbers();
        verify(accounts, atLeastOnce()).findAllWhatsAppByScope(SCOPE_A);
        verify(accounts, never()).findAllWhatsAppForSync();
        verify(scopes).touchSyncResult(SCOPE_A, "SUCCESS", null);
        verify(accounts).markWhatsAppProviderUnavailable(eq(SCOPE_A), any(Instant.class));
        verify(accounts, atLeastOnce()).upsertAdminSynced(any(), eq(SCOPE_A), any(Instant.class));
        verify(accounts).refreshAdminSynced(eq(EXISTING_ID), eq(SCOPE_A), eq("CRM Test"), eq("60111111111"),
                eq("60111111111"), eq("VERIFIED"), eq("ACTIVE"), eq("encrypted-config"), any(Instant.class));
        verify(accounts, never()).refreshAdminSynced(eq(FOREIGN_ID), any(), any(), any(), any(), any(), any(),
                any(), any(Instant.class));
        verify(accounts, never()).upsertAdminSynced(
                argThat(account -> account != null && FOREIGN_ID.equals(account.getId())),
                any(), any(Instant.class));
        verify(accounts, never()).upsertAdminSynced(any(), eq(SCOPE_B), any(Instant.class));
        verify(accounts, never()).refreshAdminSynced(any(), eq(SCOPE_B), any(), any(), any(), any(), any(),
                any(), any(Instant.class));
    }

    @Test
    void syncsAReadyBusinessAppSpaceTheAdminSelected() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppCamsConfigService camsConfig = mock(WhatsAppCamsConfigService.class);
        RoleMapper roles = adminRoles();
        WhatsAppProviderScopeEntity businessScope = scope(SCOPE_A, "space-a");
        businessScope.setScopeType("EMPLOYEE_BUSINESS_APP");
        businessScope.setOwnerUserId(OWNER);
        when(camsConfig.requireReadyScope(SCOPE_A)).thenReturn(businessScope);
        when(accounts.findAllWhatsAppByScope(SCOPE_A)).thenReturn(List.of());
        when(gateway.syncConfiguredPhoneNumbers(businessScope)).thenReturn(List.of(
                new WhatsAppOnboardingGateway.ProviderPhone("+60 1111 11111", "小森", "ACTIVE", "VERIFIED")));
        when(gateway.encryptedProviderConfig(anyString())).thenReturn("phone-encrypted-config");

        AdminWhatsAppAccountSyncService.SyncResult result = scopedService(
                config(), gateway, scopes, accounts, roles, camsConfig).sync(ACTOR, SCOPE_A);

        assertThat(result.importedCount()).isEqualTo(1);
        assertThat(result.providerPhones()).singleElement()
                .satisfies(phone -> assertThat(phone.accepted()).isTrue());
        verify(accounts).upsertAdminSynced(any(), eq(SCOPE_A), any(Instant.class));
        verify(scopes).touchSyncResult(SCOPE_A, "SUCCESS", null);
    }

    @Test
    void blockedScopeSurfacesAsConflictInsteadOfSilentlySyncing() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppCamsConfigService camsConfig = mock(WhatsAppCamsConfigService.class);
        RoleMapper roles = adminRoles();
        when(camsConfig.requireReadyScope(SCOPE_A)).thenThrow(
                new WhatsAppAuthorizationException("WHATSAPP_CAMS_SCOPE_BLOCKED", HttpStatus.CONFLICT));

        AdminWhatsAppAccountSyncService service = scopedService(
                config(), gateway, scopes, accounts, roles, camsConfig);

        assertThatThrownBy(() -> service.sync(ACTOR, SCOPE_A))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_CAMS_SCOPE_BLOCKED")
                .satisfies(error -> assertThat(((WhatsAppAuthorizationException) error).status())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(gateway, never()).syncConfiguredPhoneNumbers(any(WhatsAppProviderScopeEntity.class));
        verify(gateway, never()).syncConfiguredPhoneNumbers();
        verify(accounts, never()).findAllWhatsAppByScope(any());
        verify(accounts, never()).findAllWhatsAppForSync();
    }

    @Test
    void gatewayFailureRecordsFailedProjectionWhenNoTransactionIsActive() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppCamsConfigService camsConfig = mock(WhatsAppCamsConfigService.class);
        RoleMapper roles = adminRoles();
        WhatsAppProviderScopeEntity scopeA = scope(SCOPE_A, "space-a");
        when(camsConfig.requireReadyScope(SCOPE_A)).thenReturn(scopeA);
        when(accounts.findAllWhatsAppByScope(SCOPE_A)).thenReturn(List.of());
        when(gateway.syncConfiguredPhoneNumbers(scopeA)).thenThrow(
                new WhatsAppAuthorizationException("WHATSAPP_CAMS_CREDENTIALS_REJECTED", HttpStatus.UNAUTHORIZED));

        AdminWhatsAppAccountSyncService service = scopedService(
                config(), gateway, scopes, accounts, roles, camsConfig);

        assertThatThrownBy(() -> service.sync(ACTOR, SCOPE_A))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_CAMS_CREDENTIALS_REJECTED");
        // Without an active transaction there is nothing to defer to, so the projection is written
        // straight away.
        verify(scopes).touchSyncResult(SCOPE_A, "FAILED", "WHATSAPP_CAMS_CREDENTIALS_REJECTED");
        verify(scopes, never()).touchSyncResult(SCOPE_A, "SUCCESS", null);
    }

    private static AdminWhatsAppAccountSyncService service(AppConfig config,
                                                             WhatsAppOnboardingGateway gateway,
                                                             WhatsAppProviderScopeMapper scopes,
                                                             ChannelAccountMapper accounts) {
        return new AdminWhatsAppAccountSyncService(config, gateway, scopes, accounts);
    }

    private static AdminWhatsAppAccountSyncService scopedService(AppConfig config,
                                                                  WhatsAppOnboardingGateway gateway,
                                                                  WhatsAppProviderScopeMapper scopes,
                                                                  ChannelAccountMapper accounts,
                                                                  RoleMapper roles,
                                                                  WhatsAppCamsConfigService camsConfig) {
        return new AdminWhatsAppAccountSyncService(config, gateway, scopes, accounts, roles, camsConfig);
    }

    private static RoleMapper adminRoles() {
        RoleMapper roles = mock(RoleMapper.class);
        when(roles.userHasRole(ACTOR, "admin")).thenReturn(true);
        return roles;
    }

    private static WhatsAppProviderScopeEntity scope(UUID id, String externalScopeId) {
        WhatsAppProviderScopeEntity scope = new WhatsAppProviderScopeEntity();
        scope.setId(id);
        scope.setProvider("ALIYUN_CAMS");
        scope.setExternalScopeId(externalScopeId);
        scope.setScopeType("ENTERPRISE_API");
        scope.setStatus("READY");
        return scope;
    }

    private static AppConfig config() {
        AppConfig config = mock(AppConfig.class);
        when(config.custSpaceId()).thenReturn("space-1");
        when(config.chatappFrom()).thenReturn("60111111111");
        return config;
    }

    private static WhatsAppProviderScopeEntity scope() {
        WhatsAppProviderScopeEntity scope = new WhatsAppProviderScopeEntity();
        scope.setId(SCOPE_ID);
        scope.setProvider("ALIYUN_CAMS");
        scope.setExternalScopeId("space-1");
        scope.setScopeType("ENTERPRISE_API");
        scope.setStatus("READY");
        return scope;
    }

    private static ChannelAccountEntity account(UUID id, UUID owner, String phone,
                                                 String authStatus, String providerStatus) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(id);
        account.setOwnerUserId(owner);
        account.setChannelType("chatapp");
        account.setName("CRM Test");
        account.setAccountIdentifier(phone);
        account.setAccountIdentifierNormalized(phone);
        account.setAuthStatus(authStatus);
        account.setSyncStatus("success");
        account.setProviderPhoneStatus(providerStatus);
        account.setPhoneVerificationStatus("VERIFIED");
        account.setEncryptedConfig("encrypted-config");
        account.setLastSyncedAt(Instant.now());
        account.setVersion(2L);
        return account;
    }

    private static ChannelAccountEntity unavailable(ChannelAccountEntity source) {
        ChannelAccountEntity account = account(source.getId(), source.getOwnerUserId(),
                source.getAccountIdentifier(), source.getAuthStatus(), "UNKNOWN");
        account.setSyncStatus("failed");
        return account;
    }
}
