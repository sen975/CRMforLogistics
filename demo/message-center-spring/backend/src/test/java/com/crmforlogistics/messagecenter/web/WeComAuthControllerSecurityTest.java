package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.wecom.WeComLoginApplicationService;
import com.crmforlogistics.messagecenter.service.wecom.WeComLoginAttemptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = WeComAuthController.class, properties = {
        "app.wecom-enabled=true",
        "app.wecom-suite-id=test"
})
@Import(SecurityConfig.class)
class WeComAuthControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean WeComLoginAttemptService attempts;
    @MockitoBean WeComLoginApplicationService login;
    @MockitoBean AuthSessionService sessions;

    @Test
    void loginAttemptIsPublic() throws Exception {
        when(attempts.createLoginAttempt(anyString())).thenReturn(
                new WeComLoginAttemptService.LoginAttemptResponse(
                        "CorpApp", "corp", "agent", "http://localhost/", "state", 300));
        mvc.perform(post("/api/auth/wecom/attempts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("state"))
                .andExpect(jsonPath("$.scope").doesNotExist());
    }

    @Test
    void bindingStatusRequiresCrmAuthentication() throws Exception {
        mvc.perform(get("/api/account/wecom-binding"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exchangeRejectsUnknownRequestFields() throws Exception {
        mvc.perform(post("/api/auth/wecom/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"c\",\"state\":\"s\",\"userId\":\"attacker\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WECOM_LOGIN_REQUEST_INVALID"));
    }

    @Test
    void loginAttemptRateLimitReturnsStructuredTooManyRequests() throws Exception {
        when(attempts.createLoginAttempt(anyString()))
                .thenThrow(new WeComLoginAttemptService.PendingLimitException("capacity exceeded"));

        mvc.perform(post("/api/auth/wecom/attempts"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("WECOM_LOGIN_ATTEMPT_RATE_LIMITED"));
    }
}
