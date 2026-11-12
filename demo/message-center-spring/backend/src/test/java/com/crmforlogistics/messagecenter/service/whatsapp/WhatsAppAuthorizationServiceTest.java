package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppAuthorizationAttemptEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppAuthorizationPhoneCandidateEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppAuthorizationAttemptMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppAuthorizationPhoneCandidateMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class WhatsAppAuthorizationServiceTest {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID ATTEMPT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID SCOPE_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-09-09T03:00:00Z");

    @Test
    void businessAppCompletionUsesSyncedProviderPhoneWithoutEnterpriseScope() {
        WhatsAppAuthorizationAttemptMapper attempts = mock(WhatsAppAuthorizationAttemptMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppAuthorizationAttemptEntity attempt = pendingAttempt(USER_ID, "state");
        attempt.setOnboardingMode("EMPLOYEE_BUSINESS_APP");
        when(attempts.findByIdForUpdate(ATTEMPT_ID)).thenReturn(attempt);
        when(gateway.bindWaba("waba-1")).thenReturn(new WhatsAppOnboardingGateway.BoundScope("space-1", "waba-1"));
        when(gateway.syncPhoneNumbers(any())).thenReturn(java.util.List.of(new WhatsAppOnboardingGateway.ProviderPhone(
                "60111111111", "Sales One", "ACTIVE", "VERIFIED")));
        when(scopes.insert(any(WhatsAppProviderScopeEntity.class))).thenReturn(1);
        when(accounts.countActiveByOwnerAndChannel(USER_ID, "chatapp")).thenReturn(0);
        when(accounts.findActiveByNormalizedIdentifier("60111111111")).thenReturn(null);
        when(accounts.insertOwned(any(ChannelAccountEntity.class), eq(USER_ID))).thenReturn(1);
        when(attempts.advanceMetaCompleted(any(), any(), any(), any())).thenReturn(1);
        when(attempts.markProviderSynced(any(), any())).thenReturn(1);
        when(attempts.complete(any(), any(), any())).thenReturn(1);
        WhatsAppAuthorizationService service = service(attempts, accounts, scopes, gateway);

        WhatsAppAuthorizationService.CompletionProjection result = service.completeAuthorization(
                USER_ID, new WhatsAppAuthorizationService.SelfServiceCompletionCommand(
                        ATTEMPT_ID, "state", "FINISH", "code-must-stay-local", "waba-1", "meta-phone-id"));

        assertThat(result.phoneNumberLast4()).isEqualTo("1111");
        verify(scopes, never()).findEnterpriseApiScope(any());
        verify(gateway).verifyEmbeddedCode("code-must-stay-local");
        verify(gateway).bindWaba("waba-1");
        verify(accounts).insertOwned(any(ChannelAccountEntity.class), eq(USER_ID));
    }

    @Test
    void multipleProviderPhonesReturnPersistedOpaqueChoicesWithoutCreatingAccount() {
        WhatsAppAuthorizationAttemptMapper attempts = mock(WhatsAppAuthorizationAttemptMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppAuthorizationPhoneCandidateMapper candidates =
                mock(WhatsAppAuthorizationPhoneCandidateMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppAuthorizationAttemptEntity attempt = pendingAttempt(USER_ID, "state");
        attempt.setOnboardingMode("EMPLOYEE_BUSINESS_APP");
        when(attempts.findByIdForUpdate(ATTEMPT_ID)).thenReturn(attempt);
        when(gateway.bindWaba("waba-1")).thenReturn(new WhatsAppOnboardingGateway.BoundScope("space-1", "waba-1"));
        when(scopes.insert(any(WhatsAppProviderScopeEntity.class))).thenReturn(1);
        when(gateway.syncPhoneNumbers(any())).thenReturn(java.util.List.of(
                new WhatsAppOnboardingGateway.ProviderPhone("60111111111", "Sales", "ACTIVE", "VERIFIED"),
                new WhatsAppOnboardingGateway.ProviderPhone("60222222222", "Support", "ACTIVE", "VERIFIED")));
        when(attempts.advanceMetaCompleted(any(), any(), any(), any())).thenReturn(1);
        when(candidates.insertCandidate(any())).thenReturn(1);
        WhatsAppAuthorizationService service = new WhatsAppAuthorizationService(
                attempts, accounts, scopes, candidates, gateway, null, null,
                Clock.fixed(NOW, ZoneOffset.UTC));

        WhatsAppAuthorizationService.CompletionProjection result = service.completeAuthorization(
                USER_ID, new WhatsAppAuthorizationService.SelfServiceCompletionCommand(
                        ATTEMPT_ID, "state", "FINISH", "code", "waba-1", "meta-phone-id"));

        assertThat(result.phoneSelectionRequired()).isTrue();
        assertThat(result.phoneCandidates()).hasSize(2)
                .allSatisfy(candidate -> {
                    assertThat(candidate.candidateId()).isNotBlank();
                    assertThat(candidate.maskedPhone()).startsWith("***");
                });
        verify(candidates, times(2)).insertCandidate(any());
        verify(accounts, never()).insertOwned(any(), any());
    }

    @Test
    void selectedCandidateIsRevalidatedAgainstFreshProviderPhonesBeforeAccountCreation() {
        WhatsAppAuthorizationAttemptMapper attempts = mock(WhatsAppAuthorizationAttemptMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppAuthorizationPhoneCandidateMapper candidates =
                mock(WhatsAppAuthorizationPhoneCandidateMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppAuthorizationAttemptEntity attempt = pendingAttempt(USER_ID, "state");
        attempt.setStatus("META_COMPLETED");
        attempt.setOnboardingMode("EMPLOYEE_BUSINESS_APP");
        attempt.setCompletedWabaId("waba-1");
        when(attempts.findByIdForUpdate(ATTEMPT_ID)).thenReturn(attempt);
        WhatsAppProviderScopeEntity ownedScope = scope();
        ownedScope.setScopeType("EMPLOYEE_BUSINESS_APP");
        ownedScope.setOwnerUserId(USER_ID);
        when(scopes.findOwnedBusinessAppScope("ALIYUN_CAMS", "waba-1", USER_ID)).thenReturn(ownedScope);
        when(gateway.syncPhoneNumbers(ownedScope)).thenReturn(java.util.List.of(
                new WhatsAppOnboardingGateway.ProviderPhone("60111111111", "Sales", "ACTIVE", "VERIFIED"),
                new WhatsAppOnboardingGateway.ProviderPhone("60222222222", "Support", "ACTIVE", "VERIFIED")));
        WhatsAppAuthorizationPhoneCandidateEntity candidate = new WhatsAppAuthorizationPhoneCandidateEntity();
        candidate.setAttemptId(ATTEMPT_ID);
        candidate.setPhoneNumber("60222222222");
        candidate.setExpiresAt(NOW.plusSeconds(300));
        when(candidates.findValid(ATTEMPT_ID, WhatsAppAuthorizationService.hashState("opaque-choice"), NOW))
                .thenReturn(candidate);
        when(accounts.findActiveByNormalizedIdentifier("60222222222")).thenReturn(null);
        when(accounts.countActiveByOwnerAndChannel(USER_ID, "chatapp")).thenReturn(0);
        when(attempts.markProviderSynced(ATTEMPT_ID, "60222222222")).thenReturn(1);
        when(accounts.insertOwned(any(ChannelAccountEntity.class), eq(USER_ID))).thenReturn(1);
        when(attempts.complete(any(), any(), any())).thenReturn(1);
        WhatsAppAuthorizationService service = new WhatsAppAuthorizationService(
                attempts, accounts, scopes, candidates, gateway, null, null,
                Clock.fixed(NOW, ZoneOffset.UTC));

        WhatsAppAuthorizationService.CompletionProjection result = service.selectBusinessAppPhone(
                USER_ID, new WhatsAppAuthorizationService.PhoneSelectionCommand(
                        ATTEMPT_ID, "state", "opaque-choice"));

        assertThat(result.phoneNumberLast4()).isEqualTo("2222");
        verify(accounts).insertOwned(org.mockito.ArgumentMatchers.argThat(account ->
                "60222222222".equals(account.getAccountIdentifierNormalized())), eq(USER_ID));
        verify(candidates).deleteByAttempt(ATTEMPT_ID);
    }

    @Test
    void createsAnAttemptWithoutPhoneOrProviderMigrationCall() {
        WhatsAppAuthorizationAttemptMapper attempts = mock(WhatsAppAuthorizationAttemptMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppAuthorizationService service = service(attempts, mock(ChannelAccountMapper.class),
                mock(WhatsAppProviderScopeMapper.class), gateway);
        when(attempts.insert(any(WhatsAppAuthorizationAttemptEntity.class))).thenReturn(1);
        when(gateway.startupProfile("EMPLOYEE_BUSINESS_APP")).thenReturn(
                new WhatsAppOnboardingGateway.StartupProfile("cams-app", "cams-config",
                        "EMPLOYEE_BUSINESS_APP"));

        WhatsAppAuthorizationService.AttemptProjection projection = service.createAttempt(USER_ID);

        assertThat(projection.attemptId()).isNotNull();
        assertThat(projection.state()).isNotBlank();
        verify(attempts).insert(any(WhatsAppAuthorizationAttemptEntity.class));
    }

    @Test
    void createsEmbeddedSignupAttemptWithProviderStartupProfileAndAccountMetadata() {
        WhatsAppAuthorizationAttemptMapper attempts = mock(WhatsAppAuthorizationAttemptMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        when(attempts.insert(any(WhatsAppAuthorizationAttemptEntity.class))).thenReturn(1);
        when(gateway.startupProfile("EMPLOYEE_BUSINESS_APP")).thenReturn(
                new WhatsAppOnboardingGateway.StartupProfile("cams-app", "cams-config",
                        "EMPLOYEE_BUSINESS_APP"));
        WhatsAppAuthorizationService service = service(attempts, mock(ChannelAccountMapper.class),
                mock(WhatsAppProviderScopeMapper.class), gateway);

        WhatsAppAuthorizationService.AttemptProjection result = service.createAttempt(USER_ID,
                "BUSINESS_APP_COEXISTENCE", "销售账号", "东南亚客户");

        assertThat(result.startupProfile().appId()).isEqualTo("cams-app");
        assertThat(result.startupProfile().configId()).isEqualTo("cams-config");
        verify(attempts).insert(org.mockito.ArgumentMatchers.argThat((WhatsAppAuthorizationAttemptEntity attempt) ->
                "销售账号".equals(attempt.getAccountName())
                        && "东南亚客户".equals(attempt.getAccountRemark())));
    }

    @Test
    void rejectsNonAdminEnterpriseWabaAttemptBeforeCallingProvider() {
        WhatsAppAuthorizationAttemptMapper attempts = mock(WhatsAppAuthorizationAttemptMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        RoleMapper roles = mock(RoleMapper.class);
        when(roles.userHasRole(USER_ID, "admin")).thenReturn(false);
        WhatsAppAuthorizationService service = service(attempts, mock(ChannelAccountMapper.class),
                mock(WhatsAppProviderScopeMapper.class), gateway, roles);

        assertThatThrownBy(() -> service.createAttempt(USER_ID, "ADMIN_API_WABA", "企业账号", null))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_ENTERPRISE_WABA_ADMIN_REQUIRED");
        verify(attempts, never()).insert(any(WhatsAppAuthorizationAttemptEntity.class));
        verify(gateway, never()).startupProfile(any());
    }

    @Test
    void adminEnterpriseWabaCompletionCreatesScopeWithoutEmployeeAccount() {
        WhatsAppAuthorizationAttemptMapper attempts = mock(WhatsAppAuthorizationAttemptMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        RoleMapper roles = mock(RoleMapper.class);
        WhatsAppAuthorizationAttemptEntity attempt = pendingAttempt(USER_ID, "state");
        attempt.setOnboardingMode("ADMIN_API_WABA");
        when(attempts.findByIdForUpdate(ATTEMPT_ID)).thenReturn(attempt);
        when(roles.userHasRole(USER_ID, "admin")).thenReturn(true);
        when(gateway.bindWaba("waba-enterprise")).thenReturn(
                new WhatsAppOnboardingGateway.BoundScope("space-enterprise", "waba-enterprise"));
        when(scopes.insert(any(WhatsAppProviderScopeEntity.class))).thenReturn(1);
        when(gateway.syncPhoneNumbers(any())).thenReturn(java.util.List.of());
        when(attempts.advanceMetaCompleted(any(), any(), any(), any())).thenReturn(1);
        when(attempts.markScopeProviderSynced(any())).thenReturn(1);
        when(attempts.completeScope(any(), any())).thenReturn(1);
        WhatsAppAuthorizationService service = service(attempts, accounts, scopes, gateway, roles);

        WhatsAppAuthorizationService.CompletionProjection result = service.completeAuthorization(
                USER_ID, new WhatsAppAuthorizationService.SelfServiceCompletionCommand(
                        ATTEMPT_ID, "state", "FINISH", "code", "waba-enterprise", "meta-phone-id"));

        assertThat(result.accountId()).isNull();
        assertThat(result.onboardingMode()).isEqualTo("ADMIN_API_WABA");
        verify(scopes).insert(org.mockito.ArgumentMatchers.argThat((WhatsAppProviderScopeEntity scope) ->
                "ENTERPRISE_API".equals(scope.getScopeType()) && scope.getOwnerUserId() == null));
        verify(accounts, never()).insertOwned(any(), any());
        verify(gateway).syncPhoneNumbers(any());
    }

    @Test
    void rejectsNonAdminEnterpriseWabaCompletionBeforeCallingProvider() {
        WhatsAppAuthorizationAttemptMapper attempts = mock(WhatsAppAuthorizationAttemptMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        RoleMapper roles = mock(RoleMapper.class);
        WhatsAppAuthorizationAttemptEntity attempt = pendingAttempt(USER_ID, "state");
        attempt.setOnboardingMode("ADMIN_API_WABA");
        when(attempts.findByIdForUpdate(ATTEMPT_ID)).thenReturn(attempt);
        when(roles.userHasRole(USER_ID, "admin")).thenReturn(false);
        WhatsAppAuthorizationService service = service(attempts, mock(ChannelAccountMapper.class),
                mock(WhatsAppProviderScopeMapper.class), gateway, roles);

        assertThatThrownBy(() -> service.completeAuthorization(USER_ID,
                new WhatsAppAuthorizationService.SelfServiceCompletionCommand(
                        ATTEMPT_ID, "state", "FINISH", "code", "waba-enterprise", "meta-phone-id")))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_ENTERPRISE_WABA_ADMIN_REQUIRED");
        verify(gateway, never()).verifyEmbeddedCode(any());
        verify(gateway, never()).bindWaba(any());
    }

    @Test
    void rejectsEnterpriseWabaReplacementWhenCurrentScopeHasActiveAccounts() {
        WhatsAppAuthorizationAttemptMapper attempts = mock(WhatsAppAuthorizationAttemptMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        RoleMapper roles = mock(RoleMapper.class);
        WhatsAppAuthorizationAttemptEntity attempt = pendingAttempt(USER_ID, "state");
        attempt.setOnboardingMode("ADMIN_API_WABA");
        WhatsAppProviderScopeEntity current = scope();
        current.setScopeType("ENTERPRISE_API");
        when(attempts.findByIdForUpdate(ATTEMPT_ID)).thenReturn(attempt);
        when(roles.userHasRole(USER_ID, "admin")).thenReturn(true);
        when(gateway.bindWaba("waba-new")).thenReturn(
                new WhatsAppOnboardingGateway.BoundScope("space-new", "waba-new"));
        when(scopes.findEnterpriseApiScope("ALIYUN_CAMS")).thenReturn(current);
        when(accounts.findActiveByScope(SCOPE_ID)).thenReturn(java.util.List.of(new ChannelAccountEntity()));
        WhatsAppAuthorizationService service = service(attempts, accounts, scopes, gateway, roles);

        assertThatThrownBy(() -> service.completeAuthorization(USER_ID,
                new WhatsAppAuthorizationService.SelfServiceCompletionCommand(
                        ATTEMPT_ID, "state", "FINISH", "code", "waba-new", "meta-phone-id")))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_ENTERPRISE_WABA_REPLACEMENT_CONFLICT");
        verify(scopes, never()).blockEnterpriseApiScope(any());
        verify(scopes, never()).insert(any(WhatsAppProviderScopeEntity.class));
    }

    @Test
    void completedAttemptReturnsOriginalProjectionWithoutCallingProviderAgain() {
        WhatsAppAuthorizationAttemptMapper attempts = mock(WhatsAppAuthorizationAttemptMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppAuthorizationAttemptEntity attempt = pendingAttempt(USER_ID, "state");
        attempt.setOnboardingMode("EMPLOYEE_BUSINESS_APP");
        attempt.setStatus("COMPLETED");
        attempt.setCompletedWabaId("waba-1");
        attempt.setCompletedPhoneNumberId("meta-phone-id");
        attempt.setCompletedPhoneNumber("60111111111");
        UUID accountId = UUID.fromString("50000000-0000-0000-0000-000000000005");
        attempt.setCompletedAccountId(accountId);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ChannelAccountEntity activeAccount = new ChannelAccountEntity();
        activeAccount.setId(accountId);
        activeAccount.setOwnerUserId(USER_ID);
        activeAccount.setChannelType("chatapp");
        activeAccount.setAuthStatus("active");
        when(attempts.findByIdForUpdate(ATTEMPT_ID)).thenReturn(attempt);
        when(accounts.findByIdAndOwner(accountId, USER_ID)).thenReturn(activeAccount);
        WhatsAppAuthorizationService service = service(attempts, accounts,
                mock(WhatsAppProviderScopeMapper.class), gateway);

        WhatsAppAuthorizationService.CompletionProjection result = service.completeAuthorization(USER_ID,
                new WhatsAppAuthorizationService.SelfServiceCompletionCommand(
                        ATTEMPT_ID, "state", "FINISH", "another-code", "waba-1", "meta-phone-id"));

        assertThat(result.accountId()).isEqualTo(accountId);
        assertThat(result.phoneNumberLast4()).isEqualTo("1111");
        verify(gateway, never()).verifyEmbeddedCode(any());
        verify(gateway, never()).bindWaba(any());
    }

    @Test
    void completedAttemptCannotReplayAfterItsAccountWasUnlinked() {
        WhatsAppAuthorizationAttemptMapper attempts = mock(WhatsAppAuthorizationAttemptMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppAuthorizationAttemptEntity attempt = pendingAttempt(USER_ID, "state");
        attempt.setOnboardingMode("EMPLOYEE_BUSINESS_APP");
        attempt.setStatus("COMPLETED");
        attempt.setCompletedPhoneNumber("60111111111");
        UUID accountId = UUID.fromString("50000000-0000-0000-0000-000000000005");
        attempt.setCompletedAccountId(accountId);
        ChannelAccountEntity disabled = new ChannelAccountEntity();
        disabled.setId(accountId);
        disabled.setOwnerUserId(USER_ID);
        disabled.setChannelType("chatapp");
        disabled.setAuthStatus("disabled");
        when(attempts.findByIdForUpdate(ATTEMPT_ID)).thenReturn(attempt);
        when(accounts.findByIdAndOwner(accountId, USER_ID)).thenReturn(disabled);
        WhatsAppAuthorizationService service = service(attempts, accounts,
                mock(WhatsAppProviderScopeMapper.class), gateway);

        assertThatThrownBy(() -> service.completeAuthorization(USER_ID,
                new WhatsAppAuthorizationService.SelfServiceCompletionCommand(
                        ATTEMPT_ID, "state", "FINISH", "another-code", "waba-1", "meta-phone-id")))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_AUTH_COMPLETED_ACCOUNT_UNAVAILABLE");
        verify(gateway, never()).verifyEmbeddedCode(any());
    }

    @Test
    void doesNotRequireBrowserPhoneWhenProviderReturnsTheOnlySynchronizedPhone() {
        WhatsAppAuthorizationAttemptMapper attempts = mock(WhatsAppAuthorizationAttemptMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppAuthorizationAttemptEntity attempt = pendingAttempt(USER_ID, "state");
        attempt.setOnboardingMode("EMPLOYEE_BUSINESS_APP");
        when(attempts.findByIdForUpdate(ATTEMPT_ID)).thenReturn(attempt);
        when(gateway.bindWaba("waba-1")).thenReturn(new WhatsAppOnboardingGateway.BoundScope("space-1", "waba-1"));
        when(scopes.insert(any(WhatsAppProviderScopeEntity.class))).thenReturn(1);
        when(gateway.syncPhoneNumbers(any())).thenReturn(java.util.List.of(new WhatsAppOnboardingGateway.ProviderPhone(
                "60111111111", "Sales One", "ACTIVE", "VERIFIED")));
        when(accounts.findActiveByNormalizedIdentifier("60111111111")).thenReturn(null);
        when(accounts.countActiveByOwnerAndChannel(USER_ID, "chatapp")).thenReturn(0);
        when(accounts.insertOwned(any(ChannelAccountEntity.class), eq(USER_ID))).thenReturn(1);
        when(attempts.advanceMetaCompleted(any(), any(), any(), any())).thenReturn(1);
        when(attempts.markProviderSynced(any(), any())).thenReturn(1);
        when(attempts.complete(any(), any(), any())).thenReturn(1);
        WhatsAppAuthorizationService service = service(attempts, accounts, scopes, gateway);

        WhatsAppAuthorizationService.CompletionProjection result = service.completeAuthorization(USER_ID,
                new WhatsAppAuthorizationService.SelfServiceCompletionCommand(
                        ATTEMPT_ID, "state", "FINISH", "code", "waba-1", "phone-id-1"));

        assertThat(result.phoneNumberLast4()).isEqualTo("1111");
    }

    @Test
    void rejectsCompletionByAnotherUserBeforeCallingProvider() {
        WhatsAppAuthorizationAttemptMapper attempts = mock(WhatsAppAuthorizationAttemptMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppAuthorizationAttemptEntity attempt = pendingAttempt(USER_ID, "state");
        attempt.setOnboardingMode("EMPLOYEE_BUSINESS_APP");
        when(attempts.findByIdForUpdate(ATTEMPT_ID)).thenReturn(attempt);
        WhatsAppAuthorizationService service = service(attempts, mock(ChannelAccountMapper.class),
                mock(WhatsAppProviderScopeMapper.class), gateway);

        assertThatThrownBy(() -> service.completeAuthorization(OTHER_USER_ID,
                        new WhatsAppAuthorizationService.SelfServiceCompletionCommand(
                                ATTEMPT_ID, "state", "FINISH", "code", "waba-1", "phone-id-1")))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_AUTH_USER_MISMATCH");
        verify(gateway, never()).verifyEmbeddedCode(any());
    }

    @Test
    void rejectsNonFinishEmbeddedSignupEventsWithoutCreatingLocalAccount() {
        WhatsAppAuthorizationAttemptMapper attempts = mock(WhatsAppAuthorizationAttemptMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppAuthorizationAttemptEntity attempt = pendingAttempt(USER_ID, "state");
        attempt.setOnboardingMode("EMPLOYEE_BUSINESS_APP");
        when(attempts.findByIdForUpdate(ATTEMPT_ID)).thenReturn(attempt);
        WhatsAppAuthorizationService service = service(attempts, accounts, scopes, gateway);

        assertThatThrownBy(() -> service.completeAuthorization(USER_ID,
                        new WhatsAppAuthorizationService.SelfServiceCompletionCommand(
                                ATTEMPT_ID, "state", "CANCEL", "code", "waba-1", "phone-id-1")))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_EMBEDDED_SIGNUP_NOT_FINISHED");
        verify(gateway, never()).verifyEmbeddedCode(any());
        verify(accounts, never()).insertOwned(any(), any());
    }

    private static WhatsAppAuthorizationService service(WhatsAppAuthorizationAttemptMapper attempts,
                                                         ChannelAccountMapper accounts,
                                                         WhatsAppProviderScopeMapper scopes,
                                                         WhatsAppOnboardingGateway gateway) {
        return new WhatsAppAuthorizationService(attempts, accounts, scopes, gateway,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WhatsAppAuthorizationService service(WhatsAppAuthorizationAttemptMapper attempts,
                                                         ChannelAccountMapper accounts,
                                                         WhatsAppProviderScopeMapper scopes,
                                                         WhatsAppOnboardingGateway gateway,
                                                         RoleMapper roles) {
        return new WhatsAppAuthorizationService(attempts, accounts, scopes, gateway, null, roles,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WhatsAppAuthorizationAttemptEntity pendingAttempt(UUID userId, String state) {
        WhatsAppAuthorizationAttemptEntity attempt = new WhatsAppAuthorizationAttemptEntity();
        attempt.setId(ATTEMPT_ID);
        attempt.setUserId(userId);
        attempt.setStateHash(WhatsAppAuthorizationService.hashState(state));
        attempt.setStatus("PENDING");
        attempt.setOnboardingMode("BUSINESS_APP_COEXISTENCE");
        attempt.setExpiresAt(NOW.plusSeconds(300));
        return attempt;
    }

    private static WhatsAppProviderScopeEntity scope() {
        WhatsAppProviderScopeEntity scope = new WhatsAppProviderScopeEntity();
        scope.setId(SCOPE_ID);
        scope.setProvider("ALIYUN_CAMS");
        scope.setExternalScopeId("space-1");
        scope.setWabaId("waba-1");
        scope.setIdentityStatus("IDENTITY_VERIFIED");
        scope.setStatus("READY");
        return scope;
    }
}
