package com.crmforlogistics.messagecenter.channel.wecom;

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
@TestPropertySource(properties = {"app.wecom-suite-id=test"})
class WeComControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean WeComCallbackCodec codec;
    @MockitoBean WeComInstallationService installationService;
    @MockitoBean WeComSendService sendService;

    @Test
    void shouldHandleCallback() throws Exception {
        var callback = new WeComCallbackCodec.DecodedCallback(
                "ww123", "suite_ticket", "corp1", "code1", "ticket1", "state1",
                Instant.now());
        when(codec.decode(eq("sig1"), eq("1234567890"), eq("nonce1"), any()))
                .thenReturn(callback);

        mvc.perform(post("/api/wecom/callback")
                        .param("msg_signature", "sig1")
                        .param("timestamp", "1234567890")
                        .param("nonce", "nonce1")
                        .contentType(MediaType.TEXT_XML)
                        .content("<xml><Encrypt>test</Encrypt></xml>"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string("success"));

        verify(installationService).handleCallback(callback);
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
}
