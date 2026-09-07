package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.wecom.WeComAvatarAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = WeComAvatarAuthorizationController.class, properties = {
        "app.wecom-enabled=true",
        "app.wecom-suite-id=test"
})
@Import(SecurityConfig.class)
class WeComAvatarAuthorizationControllerTest {
    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CALLBACK = "/api/public/wecom-avatar/oauth/callback";

    @Autowired MockMvc mvc;
    @MockitoBean WeComAvatarAuthorizationService authorizations;
    @MockitoBean AuthSessionService sessions;

    @Test
    void createAndStatusRequireCrmAuthentication() throws Exception {
        mvc.perform(post("/api/account/wecom-avatar/authorizations"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/account/wecom-avatar/authorizations/authorization-id"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = USER_ID, roles = "AGENT")
    void authenticatedUserCreatesAndQueriesOnlyOwnAuthorization() throws Exception {
        when(authorizations.create(eq(UUID.fromString(USER_ID)), anyString())).thenReturn(
                new WeComAvatarAuthorizationService.AuthorizationAttempt(
                        "authorization-id", "https://open.weixin.qq.com/oauth", 
                        WeComAvatarAuthorizationService.Status.PENDING, 300));
        when(authorizations.status(UUID.fromString(USER_ID), "authorization-id")).thenReturn(
                new WeComAvatarAuthorizationService.AuthorizationStatus(
                        "authorization-id", WeComAvatarAuthorizationService.Status.PENDING, null));

        mvc.perform(post("/api/account/wecom-avatar/authorizations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationId").value("authorization-id"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/account/wecom-avatar/authorizations/authorization-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationId").value("authorization-id"));
    }

    @Test
    void onlyExactGetCallbackIsPublic() throws Exception {
        when(authorizations.complete("oauth-code", "oauth-state")).thenReturn(
                new WeComAvatarAuthorizationService.CallbackResult(
                        WeComAvatarAuthorizationService.Status.SUCCEEDED, null));

        mvc.perform(get(CALLBACK).param("code", "oauth-code").param("state", "oauth-state"))
                .andExpect(status().isOk());
        mvc.perform(post(CALLBACK).param("code", "oauth-code").param("state", "oauth-state"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void callbackRejectsUnknownAndOversizedParametersWithStructuredError() throws Exception {
        mvc.perform(get(CALLBACK)
                        .param("code", "oauth-code")
                        .param("state", "oauth-state")
                        .param("userId", USER_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WECOM_AVATAR_AUTH_REQUEST_INVALID"));

        mvc.perform(get(CALLBACK)
                        .param("code", "x".repeat(513))
                        .param("state", "oauth-state"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WECOM_AVATAR_AUTH_REQUEST_INVALID"));
    }

    @Test
    void callbackReturnsFixedSensitiveFreeHtml() throws Exception {
        when(authorizations.complete("secret-code", "secret-state")).thenReturn(
                new WeComAvatarAuthorizationService.CallbackResult(
                        WeComAvatarAuthorizationService.Status.SUCCEEDED, null));

        mvc.perform(get(CALLBACK).param("code", "secret-code").param("state", "secret-state"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("授权完成，可返回原页面")))
                .andExpect(content().string(not(containsString("secret-code"))))
                .andExpect(content().string(not(containsString("secret-state"))));
    }
}
