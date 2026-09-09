package com.crmforlogistics.messagecenter.service.whatsapp;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppPhoneOnboardingOperationEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppPhoneOnboardingOperationMapper;
import com.crmforlogistics.messagecenter.mapper.WhatsAppProviderScopeMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppPhoneNumberServiceTest {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SCOPE_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID OPERATION_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");

    @Test
    void registersApiOnlyAccountOnlyAfterProviderVerification() {
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppPhoneOnboardingOperationMapper operations = mock(WhatsAppPhoneOnboardingOperationMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        when(scopes.findEnterpriseScope("ALIYUN_CAMS")).thenReturn(scope());
        when(accounts.findActiveByNormalizedIdentifier("60111111111")).thenReturn(null);
        when(gateway.add(any(), any())).thenReturn(provider("PENDING", "PENDING"));
        when(operations.insert(any(WhatsAppPhoneOnboardingOperationEntity.class))).thenReturn(1);
        WhatsAppPhoneNumberService service = new WhatsAppPhoneNumberService(scopes, operations, accounts, gateway);

        WhatsAppPhoneNumberService.Status pending = service.start(USER_ID,
                new WhatsAppPhoneNumberService.AddCommand("60", "+60111111111", "Sales One"));

        assertThat(pending.status()).isEqualTo("PENDING");
        verify(accounts, never()).insertOwned(any(), any());
    }

    @Test
    void verificationCreatesAccountAndNeverReturnsTheVerificationCode() {
        WhatsAppProviderScopeMapper scopes = mock(WhatsAppProviderScopeMapper.class);
        WhatsAppPhoneOnboardingOperationMapper operations = mock(WhatsAppPhoneOnboardingOperationMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        WhatsAppOnboardingGateway gateway = mock(WhatsAppOnboardingGateway.class);
        when(scopes.findEnterpriseScope("ALIYUN_CAMS")).thenReturn(scope());
        when(operations.find(USER_ID, SCOPE_ID, "60111111111")).thenReturn(operation());
        when(gateway.verify(any(), any())).thenReturn(provider("ACTIVE", "VERIFIED"));
        when(accounts.findActiveByNormalizedIdentifier("60111111111")).thenReturn(null);
        when(accounts.countActiveByOwnerAndChannel(USER_ID, "chatapp")).thenReturn(0);
        when(accounts.insertOwned(any(ChannelAccountEntity.class), eq(USER_ID))).thenReturn(1);
        WhatsAppPhoneNumberService service = new WhatsAppPhoneNumberService(scopes, operations, accounts, gateway);

        WhatsAppPhoneNumberService.Status result = service.verify(USER_ID,
                new WhatsAppPhoneNumberService.VerifyCommand("+60111111111", "123456"));

        assertThat(result.status()).isEqualTo("REGISTERED");
        assertThat(result.toString()).doesNotContain("123456");
        verify(accounts).insertOwned(any(ChannelAccountEntity.class), eq(USER_ID));
        verify(operations).updateStatus(OPERATION_ID, USER_ID, "REGISTERED");
    }

    private static WhatsAppProviderScopeEntity scope() {
        WhatsAppProviderScopeEntity scope = new WhatsAppProviderScopeEntity();
        scope.setId(SCOPE_ID);
        scope.setStatus("READY");
        scope.setIdentityStatus("IDENTITY_VERIFIED");
        scope.setWabaId("waba-1");
        scope.setExternalScopeId("space-1");
        return scope;
    }

    private static WhatsAppPhoneOnboardingOperationEntity operation() {
        WhatsAppPhoneOnboardingOperationEntity operation = new WhatsAppPhoneOnboardingOperationEntity();
        operation.setId(OPERATION_ID);
        operation.setUserId(USER_ID);
        operation.setProviderScopeId(SCOPE_ID);
        operation.setPhoneNumber("60111111111");
        operation.setVerifiedName("Sales One");
        operation.setStatus("CODE_SENT");
        return operation;
    }

    private static WhatsAppOnboardingGateway.ProviderResult provider(String phoneStatus, String verificationStatus) {
        return new WhatsAppOnboardingGateway.ProviderResult("space-1", "waba-1", "60111111111",
                phoneStatus, verificationStatus, "Sales One");
    }
}
