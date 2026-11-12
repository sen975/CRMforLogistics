package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppPhoneOnboardingOperationEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppPhoneOnboardingOperationMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppPhoneNumberServiceTest {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID SCOPE_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID OPERATION_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final UUID ACCOUNT_ID = UUID.fromString("60000000-0000-0000-0000-000000000006");

    @Test
    void rejectsStartWithoutReadyEnterpriseApiScope() {
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppPhoneNumberService service = service(scopes,
                mock(WhatsAppPhoneOnboardingOperationMapper.class), mock(ChannelAccountMapper.class), gateway);

        assertThatThrownBy(() -> service.start(USER_ID,
                new WhatsAppPhoneNumberService.AddCommand("60", "+60111111111", "Provider Name",
                        "销售账号", "东南亚客户")))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_PROVIDER_SCOPE_NOT_READY");
        verify(gateway, never()).add(any(), any());
    }

    @Test
    void startPersistsOperationAndDoesNotCreateAccount() {
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppPhoneOnboardingOperationMapper operations = mock(WhatsAppPhoneOnboardingOperationMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        when(scopes.findEnterpriseApiScope("ALIYUN_CAMS")).thenReturn(scope());
        when(accounts.findActiveByNormalizedIdentifier("60111111111")).thenReturn(null);
        when(gateway.add(any(), any())).thenReturn(provider("PENDING", "PENDING"));
        when(operations.insert(any(WhatsAppPhoneOnboardingOperationEntity.class))).thenReturn(1);
        WhatsAppPhoneNumberService service = service(scopes, operations, accounts, gateway);

        WhatsAppPhoneNumberService.Status pending = service.start(USER_ID,
                new WhatsAppPhoneNumberService.AddCommand("60", "+60111111111", "Provider Name",
                        "销售账号", "东南亚客户"));

        assertThat(pending.status()).isEqualTo("PENDING");
        verify(operations).insert(org.mockito.ArgumentMatchers.argThat((WhatsAppPhoneOnboardingOperationEntity operation) ->
                "销售账号".equals(operation.getAccountName())
                        && "东南亚客户".equals(operation.getAccountRemark())));
        verify(accounts, never()).insertOwned(any(), any());
    }

    @Test
    void sendCodeRequiresExplicitConfirmation() {
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppPhoneOnboardingOperationMapper operations = mock(WhatsAppPhoneOnboardingOperationMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        when(scopes.findEnterpriseApiScope("ALIYUN_CAMS")).thenReturn(scope());
        when(operations.findByIdForUpdate(OPERATION_ID, USER_ID)).thenReturn(operation("PENDING"));
        WhatsAppPhoneNumberService service = service(scopes, operations, mock(ChannelAccountMapper.class), gateway);

        assertThatThrownBy(() -> service.sendCode(USER_ID,
                new WhatsAppPhoneNumberService.CodeCommand(OPERATION_ID, "zh_CN", "sms", false)))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_VERIFICATION_CODE_CONFIRMATION_REQUIRED");
        verify(gateway, never()).sendCode(any(), any());
    }

    @Test
    void verificationCreatesAccountOnlyFromFreshSynchronizedPhone() {
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppPhoneOnboardingOperationMapper operations = mock(WhatsAppPhoneOnboardingOperationMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        when(scopes.findEnterpriseApiScope("ALIYUN_CAMS")).thenReturn(scope());
        when(operations.findByIdForUpdate(OPERATION_ID, USER_ID)).thenReturn(operation("CODE_SENT"));
        when(gateway.verify(any(), any())).thenReturn(provider("ACTIVE", "VERIFIED"));
        when(gateway.syncPhoneNumbers(any())).thenReturn(List.of(
                new WhatsAppOnboardingGateway.ProviderPhone("60111111111", "Provider Name",
                        "ACTIVE", "VERIFIED")));
        when(accounts.findActiveByNormalizedIdentifier("60111111111")).thenReturn(null);
        when(accounts.countActiveByOwnerAndChannel(USER_ID, "chatapp")).thenReturn(0);
        when(accounts.insertOwned(any(ChannelAccountEntity.class), eq(USER_ID))).thenReturn(1);
        when(operations.complete(eq(OPERATION_ID), eq(USER_ID), any(UUID.class))).thenReturn(1);
        WhatsAppPhoneNumberService service = service(scopes, operations, accounts, gateway);

        WhatsAppPhoneNumberService.Status result = service.verify(USER_ID,
                new WhatsAppPhoneNumberService.VerifyCommand(OPERATION_ID, "123456"));

        assertThat(result.status()).isEqualTo("REGISTERED");
        assertThat(result.toString()).doesNotContain("123456");
        verify(accounts).insertOwned(org.mockito.ArgumentMatchers.argThat(account ->
                "销售账号".equals(account.getName())
                        && "东南亚客户".equals(account.getRemark())
                        && "ACTIVE".equals(account.getProviderPhoneStatus())), eq(USER_ID));
        verify(operations).complete(eq(OPERATION_ID), eq(USER_ID), any(UUID.class));
    }

    @Test
    void verificationDoesNotCreateAccountWhenFreshSyncCannotConfirmPhone() {
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppPhoneOnboardingOperationMapper operations = mock(WhatsAppPhoneOnboardingOperationMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        when(scopes.findEnterpriseApiScope("ALIYUN_CAMS")).thenReturn(scope());
        when(operations.findByIdForUpdate(OPERATION_ID, USER_ID)).thenReturn(operation("CODE_SENT"));
        when(gateway.verify(any(), any())).thenReturn(provider("ACTIVE", "VERIFIED"));
        when(gateway.syncPhoneNumbers(any())).thenReturn(List.of());
        WhatsAppPhoneNumberService service = service(scopes, operations, accounts, gateway);

        assertThatThrownBy(() -> service.verify(USER_ID,
                new WhatsAppPhoneNumberService.VerifyCommand(OPERATION_ID, "123456")))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_PHONE_NOT_READY");
        verify(accounts, never()).insertOwned(any(), any());
    }

    @Test
    void registeredOperationReturnsOriginalAccountWithoutProviderReplay() {
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppPhoneOnboardingOperationMapper operations = mock(WhatsAppPhoneOnboardingOperationMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppPhoneOnboardingOperationEntity operation = operation("REGISTERED");
        operation.setCompletedAccountId(ACCOUNT_ID);
        when(scopes.findEnterpriseApiScope("ALIYUN_CAMS")).thenReturn(scope());
        when(operations.findByIdForUpdate(OPERATION_ID, USER_ID)).thenReturn(operation);
        WhatsAppPhoneNumberService service = service(scopes, operations, mock(ChannelAccountMapper.class), gateway);

        WhatsAppPhoneNumberService.Status result = service.verify(USER_ID,
                new WhatsAppPhoneNumberService.VerifyCommand(OPERATION_ID, "different-code"));

        assertThat(result.operationId()).isEqualTo(OPERATION_ID);
        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.status()).isEqualTo("REGISTERED");
        verify(gateway, never()).verify(any(), any());
    }

    @Test
    void anotherUserCannotOperateOnTheOperation() {
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppPhoneOnboardingOperationMapper operations = mock(WhatsAppPhoneOnboardingOperationMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        when(scopes.findEnterpriseApiScope("ALIYUN_CAMS")).thenReturn(scope());
        WhatsAppPhoneNumberService service = service(scopes, operations, mock(ChannelAccountMapper.class), gateway);

        assertThatThrownBy(() -> service.verify(OTHER_USER_ID,
                new WhatsAppPhoneNumberService.VerifyCommand(OPERATION_ID, "123456")))
                .isInstanceOf(WhatsAppAuthorizationException.class)
                .hasMessage("WHATSAPP_PHONE_OPERATION_NOT_FOUND");
        verify(gateway, never()).verify(any(), any());
    }

    @Test
    void failedOperationReusesProviderRegistrationBeforeRetryingAdd() {
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppPhoneOnboardingOperationMapper operations = mock(WhatsAppPhoneOnboardingOperationMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        WhatsAppPhoneOnboardingOperationEntity failed = operation("FAILED");
        when(scopes.findEnterpriseApiScope("ALIYUN_CAMS")).thenReturn(scope());
        when(accounts.findActiveByNormalizedIdentifier("60111111111")).thenReturn(null);
        when(operations.find(USER_ID, SCOPE_ID, "60111111111")).thenReturn(failed);
        when(gateway.syncPhoneNumbers(any())).thenReturn(List.of(
                new WhatsAppOnboardingGateway.ProviderPhone("60111111111", "Provider Name", "ACTIVE", "VERIFIED")));
        when(accounts.countActiveByOwnerAndChannel(USER_ID, "chatapp")).thenReturn(0);
        when(accounts.insertOwned(any(ChannelAccountEntity.class), eq(USER_ID))).thenReturn(1);
        when(operations.complete(eq(OPERATION_ID), eq(USER_ID), any(UUID.class))).thenReturn(1);
        WhatsAppPhoneNumberService service = service(scopes, operations, accounts, gateway);

        WhatsAppPhoneNumberService.Status result = service.start(USER_ID,
                new WhatsAppPhoneNumberService.AddCommand("60", "+60111111111", "Provider Name",
                        "销售账号", "东南亚客户"));

        assertThat(result.status()).isEqualTo("REGISTERED");
        verify(gateway, never()).add(any(), any());
        verify(gateway).syncPhoneNumbers(any());
    }

    @Test
    void listReturnsOwnerScopedOperationProjection() {
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppPhoneOnboardingOperationMapper operations = mock(WhatsAppPhoneOnboardingOperationMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        when(scopes.findEnterpriseApiScope("ALIYUN_CAMS")).thenReturn(scope());
        when(operations.findAllByUserAndScope(USER_ID, SCOPE_ID)).thenReturn(List.of(operation("CODE_SENT")));
        WhatsAppPhoneNumberService service = service(scopes, operations, mock(ChannelAccountMapper.class), gateway);

        List<WhatsAppPhoneNumberService.OperationProjection> result = service.listOperations(USER_ID);

        assertThat(result).singleElement().satisfies(value -> {
            assertThat(value.operationId()).isEqualTo(OPERATION_ID);
            assertThat(value.phoneNumberLast4()).isEqualTo("1111");
            assertThat(value.accountName()).isEqualTo("销售账号");
            assertThat(value.accountRemark()).isEqualTo("东南亚客户");
            assertThat(value.status()).isEqualTo("CODE_SENT");
        });
    }

    private static WhatsAppPhoneNumberService service(WhatsAppProviderScopeMapper scopes,
                                                       WhatsAppPhoneOnboardingOperationMapper operations,
                                                       ChannelAccountMapper accounts,
                                                       WhatsAppOnboardingGateway gateway) {
        return new WhatsAppPhoneNumberService(scopes, operations, accounts, gateway);
    }

    private static WhatsAppProviderScopeEntity scope() {
        WhatsAppProviderScopeEntity scope = new WhatsAppProviderScopeEntity();
        scope.setId(SCOPE_ID);
        scope.setScopeType("ENTERPRISE_API");
        scope.setStatus("READY");
        scope.setIdentityStatus("IDENTITY_VERIFIED");
        scope.setWabaId("waba-1");
        scope.setExternalScopeId("space-1");
        return scope;
    }

    private static WhatsAppPhoneOnboardingOperationEntity operation(String status) {
        WhatsAppPhoneOnboardingOperationEntity operation = new WhatsAppPhoneOnboardingOperationEntity();
        operation.setId(OPERATION_ID);
        operation.setUserId(USER_ID);
        operation.setProviderScopeId(SCOPE_ID);
        operation.setPhoneNumber("60111111111");
        operation.setCountryCode("60");
        operation.setVerifiedName("Provider Name");
        operation.setAccountName("销售账号");
        operation.setAccountRemark("东南亚客户");
        operation.setStatus(status);
        return operation;
    }

    private static WhatsAppOnboardingGateway.ProviderResult provider(String phoneStatus,
                                                                      String verificationStatus) {
        return new WhatsAppOnboardingGateway.ProviderResult("space-1", "waba-1", "60111111111",
                phoneStatus, verificationStatus, "Provider Name");
    }
}
