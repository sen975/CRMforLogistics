package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.UpdatePhoneWebhookRequest;
import com.aliyun.sdk.service.cams20200606.models.UpdatePhoneWebhookResponse;
import com.aliyun.sdk.service.cams20200606.models.UpdatePhoneWebhookResponseBody;
import com.crmforlogistics.messagecenter.infrastructure.cams.ChatAppAccountCredentials;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppCallbackGateway;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AliyunWhatsAppCallbackGatewayTest {
    @Test
    void updatePhoneMapsAllCallbackFieldsToCamsRequest() {
        AsyncClient client = mock(AsyncClient.class);
        AtomicReference<UpdatePhoneWebhookRequest> captured = new AtomicReference<>();
        when(client.updatePhoneWebhook(any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return CompletableFuture.completedFuture(UpdatePhoneWebhookResponse.create().toBuilder()
                    .body(UpdatePhoneWebhookResponseBody.builder().code("OK").requestId("req-1").build())
                    .build());
        });
        AliyunWhatsAppCallbackGateway gateway = new AliyunWhatsAppCallbackGateway(credentials -> client);

        WhatsAppCallbackGateway.ProviderApplyResult result = gateway.updatePhone(
                new WhatsAppCallbackGateway.PhoneUpdate(
                        "space-1", "8613800001234", "https://crm.example/up",
                        "https://crm.example/status", "Y", "N"), credentials());

        assertThat(captured.get().getCustSpaceId()).isEqualTo("space-1");
        assertThat(captured.get().getPhoneNumber()).isEqualTo("8613800001234");
        assertThat(captured.get().getUpCallbackUrl()).isEqualTo("https://crm.example/up");
        assertThat(captured.get().getStatusCallbackUrl()).isEqualTo("https://crm.example/status");
        assertThat(captured.get().getHttpFlag()).isEqualTo("Y");
        assertThat(captured.get().getQueueFlag()).isEqualTo("N");
        assertThat(result.requestId()).isEqualTo("req-1");
    }

    @Test
    void accountUpdateHasNoUpstreamCallbackField() {
        AsyncClient client = mock(AsyncClient.class);
        when(client.updateAccountWebhook(any())).thenReturn(CompletableFuture.completedFuture(
                com.aliyun.sdk.service.cams20200606.models.UpdateAccountWebhookResponse.create().toBuilder()
                        .body(com.aliyun.sdk.service.cams20200606.models.UpdateAccountWebhookResponseBody.builder()
                                .code("OK").requestId("req-account").build()).build()));
        AliyunWhatsAppCallbackGateway gateway = new AliyunWhatsAppCallbackGateway(credentials -> client);

        WhatsAppCallbackGateway.ProviderApplyResult result = gateway.updateAccount(
                new WhatsAppCallbackGateway.AccountUpdate("space-1", "https://crm.example/status", "Y", "N"),
                credentials());

        assertThat(result.requestId()).isEqualTo("req-account");
    }

    private static ChatAppAccountCredentials credentials() {
        return new ChatAppAccountCredentials("key-id", "secret-value", "space-1", "8613800001234",
                "ap-southeast-1", "cams.ap-southeast-1.aliyuncs.com");
    }
}
