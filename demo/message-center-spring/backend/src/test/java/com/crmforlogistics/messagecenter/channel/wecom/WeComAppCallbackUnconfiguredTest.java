package com.crmforlogistics.messagecenter.channel.wecom;

import com.crmforlogistics.messagecenter.service.wecom.WeComAuthorizationService;
import com.crmforlogistics.messagecenter.service.wecom.WeComContactEventService;
import com.crmforlogistics.messagecenter.service.wecom.WeComStartupGate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 应用级凭据未配置时，端点必须返回 503 而不是启动失败或静默成功。
 *
 * <p>这个上下文刻意**不注册** {@link WeComAppEventCodec}（真实部署里由
 * {@code WeComConfiguration#weComAppEventCodec} 的 {@code @ConditionalOnExpression} 决定），
 * 验证「拿不到解码器就请企微重推」这条降级路径。
 */
@WebMvcTest(WeComController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "app.wecom-enabled=true",
        "app.wecom-suite-id=test"
})
class WeComAppCallbackUnconfiguredTest {

    @Autowired MockMvc mvc;
    @MockitoBean WeComCallbackCodec codec;
    @MockitoBean WeComAuthorizationService authorizationService;
    @MockitoBean WeComSendService sendService;
    @MockitoBean WeComStartupGate startupGate;
    @MockitoBean WeComContactEventService contactEventService;

    @Test
    void postIsNotAcknowledgedWhenTheAppCodecIsMissing() throws Exception {
        mvc.perform(post("/api/v1/wecom/app-callback")
                        .param("msg_signature", "sig1")
                        .param("timestamp", "1234567890")
                        .param("nonce", "nonce1")
                        .content("<xml><Encrypt>test</Encrypt></xml>"))
                .andExpect(status().isServiceUnavailable());

        verifyNoInteractions(contactEventService);
    }

    @Test
    void getEchoVerificationIsNotAnsweredWhenTheAppCodecIsMissing() throws Exception {
        mvc.perform(get("/api/v1/wecom/app-callback")
                        .param("msg_signature", "sig1")
                        .param("timestamp", "1234567890")
                        .param("nonce", "nonce1")
                        .param("echostr", "encryptedEcho"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void templateCallbackStillWorksWithoutAppCredentials() throws Exception {
        // 应用级通道缺席不得影响模板级授权回调这条生产关键路径。
        org.mockito.Mockito.when(codec.decode(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new WeComCallbackFailure(WeComCallbackFailure.Stage.SIGNATURE,
                        new SecurityException("bad signature")));

        mvc.perform(post("/api/wecom/callback")
                        .param("msg_signature", "sig1")
                        .param("timestamp", "1234567890")
                        .param("nonce", "nonce1")
                        .content("<xml><Encrypt>test</Encrypt></xml>"))
                .andExpect(status().isForbidden());
    }
}
