package com.crmforlogistics.messagecenter.service.whatsapp;

import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.ChatappBindWabaResponse;
import com.aliyun.sdk.service.cams20200606.models.ChatappBindWabaResponseBody;
import com.aliyun.sdk.service.cams20200606.models.ChatappSyncPhoneNumberResponse;
import com.aliyun.sdk.service.cams20200606.models.ChatappSyncPhoneNumberResponseBody;
import com.aliyun.sdk.service.cams20200606.models.GetPermissionByCodeResponse;
import com.aliyun.sdk.service.cams20200606.models.GetPermissionByCodeResponseBody;
import com.aliyun.sdk.service.cams20200606.models.IsvGetAppIdResponse;
import com.aliyun.sdk.service.cams20200606.models.IsvGetAppIdResponseBody;
import com.aliyun.sdk.service.cams20200606.models.QueryChatappPhoneNumbersResponse;
import com.aliyun.sdk.service.cams20200606.models.QueryChatappPhoneNumbersResponseBody;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.infrastructure.CredentialCipher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AliyunWhatsAppOnboardingGatewayTest {

    @Test
    void usesCamsPublicAuthorizationEndpointsAndReturnsProviderFacts() {
        AsyncClient client = mock(AsyncClient.class);
        IsvGetAppIdResponse startupResponse = startupResponse();
        GetPermissionByCodeResponse permission = permissionResponse();
        ChatappBindWabaResponse bind = bindResponse();
        ChatappSyncPhoneNumberResponse sync = syncResponse();
        QueryChatappPhoneNumbersResponse phonesResponse = phonesResponse();
        when(client.isvGetAppId(any())).thenReturn(CompletableFuture.completedFuture(startupResponse));
        when(client.getPermissionByCode(any())).thenReturn(CompletableFuture.completedFuture(permission));
        when(client.chatappBindWaba(any())).thenReturn(CompletableFuture.completedFuture(bind));
        when(client.chatappSyncPhoneNumber(any())).thenReturn(CompletableFuture.completedFuture(sync));
        when(client.queryChatappPhoneNumbers(any())).thenReturn(CompletableFuture.completedFuture(phonesResponse));
        AliyunWhatsAppOnboardingGateway gateway = gateway(client);

        WhatsAppOnboardingGateway.StartupProfile startup = gateway.startupProfile("EMPLOYEE_BUSINESS_APP");
        gateway.verifyEmbeddedCode("embedded-code-must-not-leak");
        WhatsAppOnboardingGateway.BoundScope bound = gateway.bindWaba("waba-1");
        WhatsAppProviderScopeEntity scope = new WhatsAppProviderScopeEntity();
        scope.setExternalScopeId(bound.custSpaceId());
        scope.setWabaId(bound.wabaId());
        List<WhatsAppOnboardingGateway.ProviderPhone> phones = gateway.syncPhoneNumbers(scope);

        assertThat(startup).isEqualTo(new WhatsAppOnboardingGateway.StartupProfile(
                "app-1", "config-1", "EMPLOYEE_BUSINESS_APP"));
        assertThat(bound).isEqualTo(new WhatsAppOnboardingGateway.BoundScope("space-1", "waba-1"));
        assertThat(phones).containsExactly(new WhatsAppOnboardingGateway.ProviderPhone(
                "60111111111", "CRM Test", "ACTIVE", "VERIFIED"));
        verify(client).isvGetAppId(org.mockito.ArgumentMatchers.argThat(request ->
                "whatsapp".equals(request.getType()) && "2".equals(request.getIntlVersion())));
        verify(client).getPermissionByCode(org.mockito.ArgumentMatchers.argThat(request ->
                "embedded-code-must-not-leak".equals(request.getCode())));
        verify(client).chatappBindWaba(org.mockito.ArgumentMatchers.argThat(request ->
                "waba-1".equals(request.getWabaId())));
        verify(client).chatappSyncPhoneNumber(org.mockito.ArgumentMatchers.argThat(request ->
                "space-1".equals(request.getCustSpaceId())));
        verify(client).queryChatappPhoneNumbers(org.mockito.ArgumentMatchers.argThat(request ->
                "space-1".equals(request.getCustSpaceId())));
    }

    private static AliyunWhatsAppOnboardingGateway gateway(AsyncClient client) {
        AppConfig config = mock(AppConfig.class);
        when(config.aliyunAccessKeyId()).thenReturn("test-key-id");
        when(config.aliyunAccessKeySecret()).thenReturn("test-key-secret");
        return new AliyunWhatsAppOnboardingGateway(config, mock(CredentialCipher.class),
                ignored -> client);
    }

    private static IsvGetAppIdResponse startupResponse() {
        IsvGetAppIdResponse response = mock(IsvGetAppIdResponse.class);
        IsvGetAppIdResponseBody body = mock(IsvGetAppIdResponseBody.class);
        when(response.getBody()).thenReturn(body);
        when(body.getCode()).thenReturn("OK");
        when(body.getAppId()).thenReturn("app-1");
        when(body.getConfigId()).thenReturn("config-1");
        return response;
    }

    private static GetPermissionByCodeResponse permissionResponse() {
        GetPermissionByCodeResponse response = mock(GetPermissionByCodeResponse.class);
        GetPermissionByCodeResponseBody body = mock(GetPermissionByCodeResponseBody.class);
        when(response.getBody()).thenReturn(body);
        when(body.getCode()).thenReturn("OK");
        return response;
    }

    private static ChatappBindWabaResponse bindResponse() {
        ChatappBindWabaResponse response = mock(ChatappBindWabaResponse.class);
        ChatappBindWabaResponseBody body = mock(ChatappBindWabaResponseBody.class);
        ChatappBindWabaResponseBody.Data data = mock(ChatappBindWabaResponseBody.Data.class);
        when(response.getBody()).thenReturn(body);
        when(body.getSuccess()).thenReturn(true);
        when(body.getData()).thenReturn(data);
        when(data.getCustSpaceId()).thenReturn("space-1");
        when(data.getWabaId()).thenReturn("waba-1");
        return response;
    }

    private static ChatappSyncPhoneNumberResponse syncResponse() {
        ChatappSyncPhoneNumberResponse response = mock(ChatappSyncPhoneNumberResponse.class);
        ChatappSyncPhoneNumberResponseBody body = mock(ChatappSyncPhoneNumberResponseBody.class);
        when(response.getBody()).thenReturn(body);
        when(body.getSuccess()).thenReturn(true);
        return response;
    }

    private static QueryChatappPhoneNumbersResponse phonesResponse() {
        QueryChatappPhoneNumbersResponse response = mock(QueryChatappPhoneNumbersResponse.class);
        QueryChatappPhoneNumbersResponseBody body = mock(QueryChatappPhoneNumbersResponseBody.class);
        QueryChatappPhoneNumbersResponseBody.PhoneNumbers phone = mock(QueryChatappPhoneNumbersResponseBody.PhoneNumbers.class);
        when(response.getBody()).thenReturn(body);
        when(body.getSuccess()).thenReturn(true);
        when(body.getPhoneNumbers()).thenReturn(List.of(phone));
        when(phone.getPhoneNumber()).thenReturn("+60 1111 11111");
        when(phone.getVerifiedName()).thenReturn("CRM Test");
        when(phone.getStatus()).thenReturn("CONNECTED");
        when(phone.getCodeVerificationStatus()).thenReturn("VERIFIED");
        return response;
    }
}
