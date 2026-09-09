package com.crmforlogistics.messagecenter.channel.chatapp;

import com.aliyun.sdk.service.cams20200606.AsyncClient;
import com.aliyun.sdk.service.cams20200606.models.AddChatappPhoneNumberResponse;
import com.aliyun.sdk.service.cams20200606.models.AddChatappPhoneNumberResponseBody;
import com.aliyun.sdk.service.cams20200606.models.ChatappVerifyAndRegisterResponse;
import com.aliyun.sdk.service.cams20200606.models.ChatappVerifyAndRegisterResponseBody;
import com.aliyun.sdk.service.cams20200606.models.GetChatappVerifyCodeResponse;
import com.aliyun.sdk.service.cams20200606.models.GetChatappVerifyCodeResponseBody;
import com.aliyun.sdk.service.cams20200606.models.GetPhoneNumberVerificationStatusResponse;
import com.aliyun.sdk.service.cams20200606.models.GetPhoneNumberVerificationStatusResponseBody;
import com.aliyun.sdk.service.cams20200606.models.QueryChatappBindWabaResponse;
import com.aliyun.sdk.service.cams20200606.models.QueryChatappBindWabaResponseBody;
import com.aliyun.sdk.service.cams20200606.models.QueryChatappPhoneNumbersResponse;
import com.aliyun.sdk.service.cams20200606.models.QueryChatappPhoneNumbersResponseBody;
import com.crmforlogistics.messagecenter.dto.response.ChatAppCapabilityReport;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatAppCapabilityProbeTest {

    private final ChatAppAccountCredentials credentials = new ChatAppAccountCredentials(
            "key-id", "key-secret", "space-1", "60111111111",
            "ap-southeast-1", "cams.ap-southeast-1.aliyuncs.com");

    @Test
    void readOnlyProbeNeverCallsProvisioningOrMigrationActions() {
        AsyncClient client = mock(AsyncClient.class);
        QueryChatappBindWabaResponse bindResponse = mock(QueryChatappBindWabaResponse.class);
        QueryChatappBindWabaResponseBody bindBody = mock(QueryChatappBindWabaResponseBody.class);
        when(bindResponse.getStatusCode()).thenReturn(200);
        when(bindResponse.getBody()).thenReturn(bindBody);
        when(bindBody.getSuccess()).thenReturn(true);
        when(bindBody.getCode()).thenReturn("OK");
        when(bindBody.getMessage()).thenReturn("SUCCESS");
        when(bindBody.getRequestId()).thenReturn("bind-request");
        when(client.queryChatappBindWaba(any())).thenReturn(CompletableFuture.completedFuture(
                bindResponse));
        QueryChatappPhoneNumbersResponse phonesResponse = mock(QueryChatappPhoneNumbersResponse.class);
        QueryChatappPhoneNumbersResponseBody phonesBody = mock(QueryChatappPhoneNumbersResponseBody.class);
        when(phonesResponse.getStatusCode()).thenReturn(200);
        when(phonesResponse.getBody()).thenReturn(phonesBody);
        when(phonesBody.getSuccess()).thenReturn(true);
        when(phonesBody.getCode()).thenReturn("OK");
        when(phonesBody.getMessage()).thenReturn("SUCCESS");
        when(phonesBody.getRequestId()).thenReturn("phones-request");
        when(client.queryChatappPhoneNumbers(any())).thenReturn(CompletableFuture.completedFuture(
                phonesResponse));
        GetPhoneNumberVerificationStatusResponse statusResponse = mock(GetPhoneNumberVerificationStatusResponse.class);
        GetPhoneNumberVerificationStatusResponseBody statusBody = mock(GetPhoneNumberVerificationStatusResponseBody.class);
        when(statusResponse.getStatusCode()).thenReturn(200);
        when(statusResponse.getBody()).thenReturn(statusBody);
        when(statusBody.getCode()).thenReturn("OK");
        when(statusBody.getMessage()).thenReturn("SUCCESS");
        when(statusBody.getRequestId()).thenReturn("status-request");
        when(client.getPhoneNumberVerificationStatus(any())).thenReturn(CompletableFuture.completedFuture(
                statusResponse));
        ChatAppCapabilityProbe probe = new ChatAppCapabilityProbe(ignored -> client);

        ChatAppCapabilityReport report = probe.probeReadOnly(credentials, credentials.chatappFrom());

        assertThat(report.phase()).isEqualTo("READ_ONLY");
        assertThat(report.ready()).isTrue();
        assertThat(report.results()).extracting(ChatAppCapabilityReport.ActionResult::action)
                .containsExactly("QueryChatappBindWaba", "QueryChatappPhoneNumbers",
                        "GetPhoneNumberVerificationStatus");
        assertThat(report.results()).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo("VERIFIED");
            assertThat(result.diagnosticMessage()).doesNotContain("key-secret");
        });
        verify(client, never()).addChatappPhoneNumber(any());
        verify(client, never()).getChatappVerifyCode(any());
        verify(client, never()).chatappVerifyAndRegister(any());
        verify(client, never()).createChatappMigrationInitiate(any());
        verify(client, never()).chatappMigrationVerified(any());
        verify(client, never()).chatappMigrationRegister(any());
    }

    @Test
    void provisioningActionsAreSeparateAndVerificationCodeIsNeverReturned() {
        AsyncClient client = mock(AsyncClient.class);
        AddChatappPhoneNumberResponse addResponse = mock(AddChatappPhoneNumberResponse.class);
        AddChatappPhoneNumberResponseBody addBody = mock(AddChatappPhoneNumberResponseBody.class);
        when(addResponse.getStatusCode()).thenReturn(200);
        when(addResponse.getBody()).thenReturn(addBody);
        when(addBody.getCode()).thenReturn("OK");
        when(addBody.getMessage()).thenReturn("SUCCESS");
        when(addBody.getRequestId()).thenReturn("add-request");
        when(client.addChatappPhoneNumber(any())).thenReturn(CompletableFuture.completedFuture(
                addResponse));
        GetChatappVerifyCodeResponse sendResponse = mock(GetChatappVerifyCodeResponse.class);
        GetChatappVerifyCodeResponseBody sendBody = mock(GetChatappVerifyCodeResponseBody.class);
        when(sendResponse.getStatusCode()).thenReturn(200);
        when(sendResponse.getBody()).thenReturn(sendBody);
        when(sendBody.getCode()).thenReturn("OK");
        when(sendBody.getMessage()).thenReturn("SUCCESS");
        when(sendBody.getRequestId()).thenReturn("code-request");
        when(client.getChatappVerifyCode(any())).thenReturn(CompletableFuture.completedFuture(
                sendResponse));
        ChatappVerifyAndRegisterResponse verifyResponse = mock(ChatappVerifyAndRegisterResponse.class);
        ChatappVerifyAndRegisterResponseBody verifyBody = mock(ChatappVerifyAndRegisterResponseBody.class);
        when(verifyResponse.getStatusCode()).thenReturn(200);
        when(verifyResponse.getBody()).thenReturn(verifyBody);
        when(verifyBody.getCode()).thenReturn("OK");
        when(verifyBody.getMessage()).thenReturn("SUCCESS");
        when(verifyBody.getRequestId()).thenReturn("verify-request");
        when(client.chatappVerifyAndRegister(any())).thenReturn(CompletableFuture.completedFuture(
                verifyResponse));
        ChatAppCapabilityProbe probe = new ChatAppCapabilityProbe(ignored -> client);

        ChatAppCapabilityReport add = probe.addTestNumber(credentials,
                new ChatAppCapabilityProbe.AddNumberCommand("60", "60122222222", "CRM Test"));
        ChatAppCapabilityReport sendCode = probe.sendVerificationCode(credentials,
                new ChatAppCapabilityProbe.SendCodeCommand("60122222222", "en_US", "sms"));
        ChatAppCapabilityReport verifyCode = probe.verifyAndRegister(credentials,
                new ChatAppCapabilityProbe.VerifyCommand("60122222222", "849201"));

        assertThat(add.results()).extracting(ChatAppCapabilityReport.ActionResult::action)
                .containsExactly("AddChatappPhoneNumber");
        assertThat(sendCode.results()).extracting(ChatAppCapabilityReport.ActionResult::action)
                .containsExactly("GetChatappVerifyCode");
        assertThat(verifyCode.results()).extracting(ChatAppCapabilityReport.ActionResult::action)
                .containsExactly("ChatappVerifyAndRegister");
        assertThat(verifyCode.toString()).doesNotContain("849201");
        verify(client).addChatappPhoneNumber(any());
        verify(client).getChatappVerifyCode(any());
        verify(client).chatappVerifyAndRegister(any());
    }

    @Test
    void migrationCapabilitiesRemainExplicitlyUnverifiedWithoutCallingProvider() {
        AsyncClient client = mock(AsyncClient.class);
        ChatAppCapabilityProbe probe = new ChatAppCapabilityProbe(ignored -> client);

        ChatAppCapabilityReport report = probe.migrationReview();

        assertThat(report.phase()).isEqualTo("MIGRATION_REVIEW");
        assertThat(report.ready()).isFalse();
        assertThat(report.results()).hasSize(3).allSatisfy(result ->
                assertThat(result.status()).isEqualTo("UNVERIFIED_FOR_PRODUCTION"));
        verify(client, never()).createChatappMigrationInitiate(any());
        verify(client, never()).chatappMigrationVerified(any());
        verify(client, never()).chatappMigrationRegister(any());
    }
}
