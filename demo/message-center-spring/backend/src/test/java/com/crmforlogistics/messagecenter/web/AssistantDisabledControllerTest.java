package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.CorsConfig;
import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.assistant.AssistantRequestGuard;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecentertest.assistant.AssistantDisabledTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 功能关闭时端点必须返回 503，而不是 404、也不是启动失败。
 *
 * <p>这个类刻意<b>不</b>提供 {@code AssistantConversationService} /
 * {@code AssistantPendingActionService} 的替身 —— 它们带 {@code @ConditionalOnAssistantEnabled}，
 * 在「未开启或凭据缺失」时根本不装配，这正是要验的场景。
 *
 * <p>为什么在意 503 与 404 的区别：404 会让前端以为是自己请求的路径写错了，
 * 于是去改代码；而真正的原因是一个没人打开过的开关。
 */
@WebMvcTest(AssistantController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class, AssistantRequestGuard.class,
        AssistantDisabledTestConfiguration.class})
class AssistantDisabledControllerTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PENDING_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Autowired MockMvc mvc;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    void messagesReturns503WhenTheFeatureIsOff() throws Exception {
        mvc.perform(post("/api/assistant/messages")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"帮我建个待办\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ASSISTANT_DISABLED"));
    }

    @Test
    void confirmAndCancelAlsoReturn503() throws Exception {
        mvc.perform(post("/api/assistant/actions/{id}/confirm", PENDING_ID)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ASSISTANT_DISABLED"));

        mvc.perform(post("/api/assistant/actions/{id}/cancel", PENDING_ID)
                        .with(user(USER_ID.toString())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ASSISTANT_DISABLED"));
    }

    /** 认证仍然先于「功能未开启」生效：未认证的请求不该知道这个功能存不存在。 */
    @Test
    void unauthenticatedRequestStillGets401() throws Exception {
        mvc.perform(post("/api/assistant/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"帮我建个待办\"}"))
                .andExpect(status().isUnauthorized());
    }
}
