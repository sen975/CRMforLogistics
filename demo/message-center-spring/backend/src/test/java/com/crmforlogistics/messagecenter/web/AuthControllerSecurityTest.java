package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.config.CorsConfig;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.account.AccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class})
class AuthControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthenticationConfiguration authenticationConfiguration;
    @MockitoBean AuthSessionService authSessionService;
    @MockitoBean AccountService accountService;

    @Test
    void registrationIsPublicAndReturnsTheIssuedSession() throws Exception {
        when(accountService.register("agent_01", "张三", "Example123", "127.0.0.1", null))
                .thenReturn(new AccountService.SessionResult("register-token", "agent_01", List.of("AGENT")));

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"agent_01\",\"displayName\":\"张三\",\"password\":\"Example123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("register-token"))
                .andExpect(jsonPath("$.roles[0]").value("AGENT"));
    }

    @Test
    void loginIsPublicAndIssuesOpaqueSession() throws Exception {
        UUID userId = UUID.randomUUID();
        AuthenticationManager manager = mock(AuthenticationManager.class);
        when(authenticationConfiguration.getAuthenticationManager()).thenReturn(manager);
        when(manager.authenticate(any())).thenReturn(new UsernamePasswordAuthenticationToken(
                userId.toString(), "", List.of(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_AGENT"))));
        when(authSessionService.issue(any(), any(), any())).thenReturn("opaque-token");

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"sales\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("opaque-token"))
                .andExpect(jsonPath("$.username").value("sales"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.roles[1]").value("AGENT"));

        verify(authSessionService).issue(userId, "127.0.0.1", null);
    }

    @Test
    void loginAllowsTheFrontendLoopbackOrigin() throws Exception {
        UUID userId = UUID.randomUUID();
        AuthenticationManager manager = mock(AuthenticationManager.class);
        when(authenticationConfiguration.getAuthenticationManager()).thenReturn(manager);
        when(manager.authenticate(any())).thenReturn(new UsernamePasswordAuthenticationToken(
                userId.toString(), "", List.of()));
        when(authSessionService.issue(any(), any(), any())).thenReturn("opaque-token");

        mvc.perform(post("/api/auth/login")
                        .header("Origin", "http://127.0.0.1:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"sales\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:5173"));
    }

    @Test
    void loginAllowsTheProductionFrontendOrigin() throws Exception {
        UUID userId = UUID.randomUUID();
        AuthenticationManager manager = mock(AuthenticationManager.class);
        when(authenticationConfiguration.getAuthenticationManager()).thenReturn(manager);
        when(manager.authenticate(any())).thenReturn(new UsernamePasswordAuthenticationToken(
                userId.toString(), "", List.of()));
        when(authSessionService.issue(any(), any(), any())).thenReturn("opaque-token");

        mvc.perform(post("/api/auth/login")
                        .header("Origin", "https://www.blindac.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"sales\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://www.blindac.com"));
    }

    @Test
    void transcriptPatchPreflightIsAllowedForTheFrontend() throws Exception {
        mvc.perform(options("/api/v1/call-records/{id}/transcript", UUID.randomUUID())
                        .header("Origin", "http://127.0.0.1:5173")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:5173"))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")));
    }

    @Test
    void invalidCredentialsReturnStructuredUnauthorizedResponse() throws Exception {
        AuthenticationManager manager = mock(AuthenticationManager.class);
        when(authenticationConfiguration.getAuthenticationManager()).thenReturn(manager);
        when(manager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"sales\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("INVALID_CREDENTIALS"));
    }

    @Test
    void logoutWithoutBearerSessionIsUnauthorized() throws Exception {
        mvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/email/send", "/api/send/email", "/api/v1/email/messages"})
    void anonymousEmailSendReturnsAuthenticationRequiredAtSecurityBoundary(String route) throws Exception {
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"person@example.test\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(multipart(route).file("file", new byte[]{1}).param("to", "person@example.test"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void logoutRevokesCurrentBearerSession() throws Exception {
        var user = User.withUsername(UUID.randomUUID().toString())
                .password("x").roles("AGENT").build();
        when(authSessionService.authenticate("opaque-token")).thenReturn(Optional.of(user));

        mvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer opaque-token"))
                .andExpect(status().isNoContent());

        verify(authSessionService).revoke("opaque-token");
    }
}
