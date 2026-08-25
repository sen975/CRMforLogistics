package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.wecom.WeComAuthorizationService;
import com.crmforlogistics.messagecenter.service.wecom.WeComStartupGate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WeComController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.wecom-enabled=true",
        "app.wecom-suite-id=test"
})
class WeComCallbackCompatibilityTest {
    private static final List<String> CALLBACK_PATHS = List.of(
            "/api/wecom/callback",
            "/api/v1/wecom/authorization/callback",
            "/hook_path");

    @Autowired MockMvc mvc;
    @MockitoBean WeComCallbackCodec codec;
    @MockitoBean WeComAuthorizationService authorizationService;
    @MockitoBean WeComSendService sendService;
    @MockitoBean WeComStartupGate startupGate;
    @MockitoBean AuthSessionService authSessionService;
    @MockitoBean org.springframework.security.core.userdetails.UserDetailsService userDetailsService;

    @Test
    void supportsCanonicalLegacyAndHookCallbackVerificationPaths() throws Exception {
        when(codec.verifyAndDecryptEcho("sig", "1", "n", "echo")).thenReturn("verified");

        for (String path : CALLBACK_PATHS) {
            mvc.perform(get(path)
                            .param("msg_signature", "sig")
                            .param("timestamp", "1")
                            .param("nonce", "n")
                            .param("echostr", "echo"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("verified"));
        }
    }

    @Test
    void supportsCanonicalLegacyAndHookCallbackEventPaths() throws Exception {
        var decoded = new WeComCallbackCodec.DecodedCallback(
                "suite", "suite_ticket", "corp", "", "ticket", "", Instant.EPOCH);
        when(codec.decode(any(), any(), any(), any())).thenReturn(decoded);
        when(authorizationService.handle(decoded)).thenReturn(WeComAuthorizationService.CallbackAck.accepted());

        for (String path : CALLBACK_PATHS) {
            mvc.perform(post(path)
                            .param("msg_signature", "sig")
                            .param("timestamp", "1")
                            .param("nonce", "n")
                            .contentType(MediaType.TEXT_XML)
                            .content("<xml><Encrypt>payload</Encrypt></xml>"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("success"));
        }
    }

    @Test
    void sendEndpointStillRequiresCrmAuthentication() throws Exception {
        mvc.perform(post("/api/wecom/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"corpId\":\"corp\",\"agentId\":\"agent\","
                                + "\"to\":\"user\",\"text\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }
}
