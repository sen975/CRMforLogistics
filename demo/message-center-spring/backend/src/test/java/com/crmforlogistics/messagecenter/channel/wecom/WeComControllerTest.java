package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.service.wecom.WeComAuthorizationService;
import com.crmforlogistics.messagecenter.service.wecom.WeComContactEventService;
import com.crmforlogistics.messagecenter.service.wecom.WeComStartupGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WeComController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "app.wecom-enabled=true",
        "app.wecom-suite-id=test"
})
class WeComControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean WeComCallbackCodec codec;
    @MockitoBean WeComAuthorizationService authorizationService;
    @MockitoBean WeComSendService sendService;
    @MockitoBean WeComStartupGate startupGate;
    @MockitoBean WeComAppEventCodec appEventCodec;
    @MockitoBean WeComContactEventService contactEventService;

    @Test
    void shouldHandleCallback() throws Exception {
        var callback = new WeComCallbackCodec.DecodedCallback(
                "ww123", "suite_ticket", "corp1", "code1", "ticket1", "state1",
                Instant.now());
        when(codec.decode(eq("sig1"), eq("1234567890"), eq("nonce1"), any()))
                .thenReturn(callback);
        when(authorizationService.handle(callback))
                .thenReturn(WeComAuthorizationService.CallbackAck.accepted());

        mvc.perform(post("/api/wecom/callback")
                        .param("msg_signature", "sig1")
                        .param("timestamp", "1234567890")
                        .param("nonce", "nonce1")
                        .contentType(MediaType.TEXT_XML)
                        .content("<xml><Encrypt>test</Encrypt></xml>"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string("success"));

        verify(authorizationService).handle(callback);
    }

    @Test
    void shouldReturnServiceUnavailableWhenAuthorizationRequestsRetry() throws Exception {
        var callback = new WeComCallbackCodec.DecodedCallback(
                "ww123", "create_auth", "corp1", "code1", "", "state1",
                Instant.now());
        when(codec.decode(eq("sig1"), eq("1234567890"), eq("nonce1"), any()))
                .thenReturn(callback);
        when(authorizationService.handle(callback))
                .thenReturn(WeComAuthorizationService.CallbackAck.retry());

        mvc.perform(post("/api/wecom/callback")
                        .param("msg_signature", "sig1")
                        .param("timestamp", "1234567890")
                        .param("nonce", "nonce1")
                        .contentType(MediaType.TEXT_XML)
                        .content("<xml><Encrypt>test</Encrypt></xml>"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void shouldVerifyCallbackEcho() throws Exception {
        when(codec.verifyAndDecryptEcho("sig1", "1234567890", "nonce1", "encryptedEcho"))
                .thenReturn("decryptedEchoValue");

        mvc.perform(get("/api/wecom/callback")
                        .param("msg_signature", "sig1")
                        .param("timestamp", "1234567890")
                        .param("nonce", "nonce1")
                        .param("echostr", "encryptedEcho"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string("decryptedEchoValue"));
    }

    @Test
    void shouldSendMessage() throws Exception {
        when(sendService.send("corp1", "agent1", "user1", "hello"))
                .thenReturn(new WeComSendService.SendResult("msg-1", "agent1",
                        "user1", "hello", "sent"));

        mvc.perform(post("/api/wecom/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "corpId", "corp1", "agentId", "agent1",
                                "to", "user1", "text", "hello"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value("msg-1"));
    }

    @Test
    void shouldReturnErrorOnSendFailure() throws Exception {
        when(sendService.send(any(), any(), any(), any()))
                .thenThrow(new WeComException("WECOM_SEND_FAILED", 502, "发送失败"));

        mvc.perform(post("/api/wecom/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "corpId", "corp1", "agentId", "agent1",
                                "to", "user1", "text", "hello"))))
                .andExpect(status().is(502))
                .andExpect(jsonPath("$.error").value("发送失败"));
    }

    // ---------- 应用级（客户联系事件）回调 ----------

    @Test
    void appCallbackAcksOnlyAfterTheEventIsDurablyIngested() throws Exception {
        var decoded = appEvent("add_external_contact");
        when(appEventCodec.decode(eq("sig1"), eq("1234567890"), eq("nonce1"), any()))
                .thenReturn(decoded);
        when(contactEventService.ingest(decoded))
                .thenReturn(WeComContactEventService.IngestResult.ACCEPTED);

        mvc.perform(post("/api/v1/wecom/app-callback")
                        .param("msg_signature", "sig1")
                        .param("timestamp", "1234567890")
                        .param("nonce", "nonce1")
                        .contentType(MediaType.TEXT_XML)
                        .content("<xml><Encrypt>test</Encrypt></xml>"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string("success"));

        verify(contactEventService).ingest(decoded);
    }

    @Test
    void appCallbackAcksRedeliveredEvents() throws Exception {
        // 企微重推同一事件：库里已有，仍然 ack，否则会一直被重推到过期。
        when(appEventCodec.decode(any(), any(), any(), any())).thenReturn(appEvent("del_external_contact"));
        when(contactEventService.ingest(any()))
                .thenReturn(WeComContactEventService.IngestResult.DUPLICATE);

        mvc.perform(post("/api/v1/wecom/app-callback")
                        .param("msg_signature", "sig1")
                        .param("timestamp", "1234567890")
                        .param("nonce", "nonce1")
                        .content("<xml><Encrypt>test</Encrypt></xml>"))
                .andExpect(status().isOk());
    }

    @Test
    void appCallbackAsksForRetryWhenTheFeatureIsDisabled() throws Exception {
        when(appEventCodec.decode(any(), any(), any(), any())).thenReturn(appEvent("add_external_contact"));
        when(contactEventService.ingest(any()))
                .thenReturn(WeComContactEventService.IngestResult.DISABLED);

        mvc.perform(post("/api/v1/wecom/app-callback")
                        .param("msg_signature", "sig1")
                        .param("timestamp", "1234567890")
                        .param("nonce", "nonce1")
                        .content("<xml><Encrypt>test</Encrypt></xml>"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string("retry"));
    }

    @Test
    void appCallbackAsksForRetryWhenTheInstallationIsUnknown() throws Exception {
        when(appEventCodec.decode(any(), any(), any(), any())).thenReturn(appEvent("add_external_contact"));
        when(contactEventService.ingest(any()))
                .thenReturn(WeComContactEventService.IngestResult.INSTALLATION_UNKNOWN);

        mvc.perform(post("/api/v1/wecom/app-callback")
                        .param("msg_signature", "sig1")
                        .param("timestamp", "1234567890")
                        .param("nonce", "nonce1")
                        .content("<xml><Encrypt>test</Encrypt></xml>"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void appCallbackAsksForRetryWhenTheDatabaseIsUnavailable() throws Exception {
        when(appEventCodec.decode(any(), any(), any(), any())).thenReturn(appEvent("add_external_contact"));
        when(contactEventService.ingest(any())).thenThrow(
                new WeComException("WECOM_CONTACT_EVENT_INGEST_FAILED", 503, "落库失败"));

        mvc.perform(post("/api/v1/wecom/app-callback")
                        .param("msg_signature", "sig1")
                        .param("timestamp", "1234567890")
                        .param("nonce", "nonce1")
                        .content("<xml><Encrypt>test</Encrypt></xml>"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void appCallbackRejectsOversizedBodyBeforeBindingItToAString() throws Exception {
        mvc.perform(post("/api/v1/wecom/app-callback")
                        .param("msg_signature", "sig1")
                        .param("timestamp", "1234567890")
                        .param("nonce", "nonce1")
                        .contentType(MediaType.TEXT_XML)
                        .content("x".repeat(1_048_577)))
                .andExpect(status().isPayloadTooLarge());

        verify(appEventCodec, org.mockito.Mockito.never()).decode(any(), any(), any(), any());
        verify(contactEventService, org.mockito.Mockito.never()).ingest(any());
    }

    @Test
    void appCallbackIsForbiddenWhenDecodingFails() throws Exception {
        // 验签/解密失败属于「请求本身不对」，重试多少次都不会变对，因此 403 而不是 503。
        when(appEventCodec.decode(any(), any(), any(), any())).thenThrow(
                new WeComCallbackFailure(WeComCallbackFailure.Stage.SIGNATURE,
                        new SecurityException("bad signature")));

        mvc.perform(post("/api/v1/wecom/app-callback")
                        .param("msg_signature", "sig1")
                        .param("timestamp", "1234567890")
                        .param("nonce", "nonce1")
                        .content("<xml><Encrypt>test</Encrypt></xml>"))
                .andExpect(status().isForbidden());

        verify(contactEventService, org.mockito.Mockito.never()).ingest(any());
    }

    @Test
    void appCallbackAsksForRetryBeforeStartupCompletes() throws Exception {
        org.mockito.Mockito.doThrow(new WeComException("WECOM_STARTUP_MIGRATION_PENDING", 503, "未就绪"))
                .when(startupGate).requireOpen();

        mvc.perform(post("/api/v1/wecom/app-callback")
                        .param("msg_signature", "sig1")
                        .param("timestamp", "1234567890")
                        .param("nonce", "nonce1")
                        .content("<xml><Encrypt>test</Encrypt></xml>"))
                .andExpect(status().isServiceUnavailable());

        verify(appEventCodec, org.mockito.Mockito.never()).decode(any(), any(), any(), any());
    }

    @Test
    void appCallbackVerifiesTheEchoString() throws Exception {
        when(appEventCodec.verifyAndDecryptEcho("sig1", "1234567890", "nonce1", "encryptedEcho"))
                .thenReturn("decryptedEchoValue");

        mvc.perform(get("/api/v1/wecom/app-callback")
                        .param("msg_signature", "sig1")
                        .param("timestamp", "1234567890")
                        .param("nonce", "nonce1")
                        .param("echostr", "encryptedEcho"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string("decryptedEchoValue"));
    }

    private static WeComAppEventCodec.DecodedAppEvent appEvent(String changeType) {
        return new WeComAppEventCodec.DecodedAppEvent("wpxxxxxxxxcorpid", "sys",
                "change_external_contact", changeType, "zhangsan", "wmZZZZZZZZ", null,
                "state-a", "WELCOMECODE", null, null, Instant.now());
    }
}
