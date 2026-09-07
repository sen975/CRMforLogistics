package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.wecom.LocalWeComDevelopmentService;
import com.crmforlogistics.messagecenter.service.wecom.WeComChatDataSyncService;
import com.crmforlogistics.messagecenter.service.wecom.WeComLoginAttemptService;
import com.crmforlogistics.messagecenter.service.wecom.WeComUserBindingService;
import com.crmforlogistics.messagecenter.service.wecom.WeComViewerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.core.userdetails.User.withUsername;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = WeComViewerController.class, properties = "app.wecom-enabled=true")
@Import(SecurityConfig.class)
class WeComViewerControllerTest {
    private static final UUID AUTHENTICATED_USER_ID = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @MockitoBean AuthSessionService authSessionService;
    @MockitoBean AppConfig config;
    @MockitoBean org.springframework.security.core.userdetails.UserDetailsService userDetailsService;
    @MockitoBean WeComViewerService viewer;
    @MockitoBean WeComUserBindingService bindingService;
    @MockitoBean WeComChatDataSyncService syncService;
    @MockitoBean LocalWeComDevelopmentService localService;

    private final WeComLoginAttemptService.InstallationBinding installation =
            new WeComLoginAttemptService.InstallationBinding("installation", 1L, "suite", "corp", "agent");
    private final WeComUserBindingService.BoundIdentity binding = new WeComUserBindingService.BoundIdentity(
            AUTHENTICATED_USER_ID, "suite", "corp", "wecom-user", "BOUND_EXISTING", installation, "user");

    @BeforeEach
    void authenticateBoundUser() {
        when(config.localDevMode()).thenReturn(false);
        when(authSessionService.authenticate("crm-token"))
                .thenReturn(java.util.Optional.of(withUsername(AUTHENTICATED_USER_ID.toString())
                        .password("x").roles("AGENT").build()));
        when(bindingService.requireByUserId(AUTHENTICATED_USER_ID)).thenReturn(binding);
        when(viewer.requireViewerActor("viewer-token")).thenReturn("wecom-user");
    }

    @Test
    void localModeUsesLocalViewerEvenWhenProductionViewerIsAvailable() throws Exception {
        when(config.localDevMode()).thenReturn(true);
        when(localService.issueViewerAuth("wecom-user", installation))
                .thenReturn(new WeComViewerService.LoginExchangeResponse("wecom-user", "local-token", 300));

        mvc.perform(post("/api/v1/wecom/conversation-view/bootstrap")
                        .header("Authorization", "Bearer crm-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerAuthToken").value("local-token"));

        verify(localService).issueViewerAuth("wecom-user", installation);
        verify(viewer, never()).issueViewerAuth(any(), any());
    }

    @Test
    void bootstrapUsesAuthenticatedUserBindingNotRequestIdentity() throws Exception {
        when(viewer.issueViewerAuth("wecom-user", installation))
                .thenReturn(new WeComViewerService.LoginExchangeResponse("wecom-user", "viewer-token", 300));

        mvc.perform(post("/api/v1/wecom/conversation-view/bootstrap")
                        .header("Authorization", "Bearer crm-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerAuthToken").isString());
        verify(bindingService).requireByUserId(AUTHENTICATED_USER_ID);
    }

    @Test
    void sessionCreationRejectsUnboundUser() throws Exception {
        when(bindingService.requireByUserId(any())).thenThrow(new WeComException(
                "WECOM_USER_NOT_BOUND", 403, "账号尚未绑定企业微信"));

        mvc.perform(post("/api/v1/wecom/conversation-view/sessions")
                        .header("Authorization", "Bearer crm-token")
                        .header("X-WeCom-Viewer-Token", "viewer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactPointId\":\"wecom:external\",\"messageIds\":[\"m1\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WECOM_USER_NOT_BOUND"));
    }

    @Test
    void viewerEndpointsRequireCrmAuthentication() throws Exception {
        mvc.perform(post("/api/v1/wecom/conversation-view/bootstrap"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sessionCreationRejectsTokenActorThatDoesNotMatchCurrentBinding() throws Exception {
        when(viewer.requireViewerActor("viewer-token")).thenReturn("other-wecom-user");

        mvc.perform(post("/api/v1/wecom/conversation-view/sessions")
                        .header("Authorization", "Bearer crm-token")
                        .header("X-WeCom-Viewer-Token", "viewer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactPointId\":\"wecom:external\",\"messageIds\":[\"m1\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WECOM_VIEWER_ACTOR_MISMATCH"));
        verify(viewer, never()).createViewerSession(any(), any(), any());
    }

    @Test
    void sessionCreationUsesOnlyBoundActorHeaderTokenAndStrictRequestFields() throws Exception {
        when(viewer.createViewerSession("wecom:external", "viewer-token", java.util.List.of("m1")))
                .thenReturn(new WeComViewerService.ViewerSessionResponse("session-1", 60));

        mvc.perform(post("/api/v1/wecom/conversation-view/sessions")
                        .header("Authorization", "Bearer crm-token")
                        .header("X-WeCom-Viewer-Token", "viewer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactPointId\":\"wecom:external\",\"messageIds\":[\"m1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerSessionId").value("session-1"));

        verify(bindingService).requireByUserId(AUTHENTICATED_USER_ID);
        verify(viewer).createViewerSession("wecom:external", "viewer-token", java.util.List.of("m1"));
    }

    @Test
    void sessionRequestRejectsViewerTokenOutsideTheDedicatedHeader() throws Exception {
        mvc.perform(post("/api/v1/wecom/conversation-view/sessions?viewerAuthToken=viewer-token")
                        .header("Authorization", "Bearer crm-token")
                        .header("X-WeCom-Viewer-Token", "viewer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactPointId\":\"wecom:external\",\"messageIds\":[\"m1\"]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/wecom/conversation-view/sessions")
                        .header("Authorization", "Bearer crm-token")
                        .header("X-WeCom-Viewer-Token", "viewer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactPointId\":\"wecom:external\",\"messageIds\":[\"m1\"],\"viewerAuthToken\":\"legacy-token\"}"))
                .andExpect(status().isBadRequest());

        verify(viewer, never()).createViewerSession(any(), any(), any());
    }

    @Test
    void sessionRequestRejectsMoreThanFifteenMessageIds() throws Exception {
        String messageIds = java.util.stream.IntStream.rangeClosed(1, 16)
                .mapToObj(index -> "\"m" + index + "\"")
                .collect(java.util.stream.Collectors.joining(","));

        mvc.perform(post("/api/v1/wecom/conversation-view/sessions")
                        .header("Authorization", "Bearer crm-token")
                        .header("X-WeCom-Viewer-Token", "viewer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactPointId\":\"wecom:external\",\"messageIds\":[" + messageIds + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WECOM_VIEWER_REQUEST_INVALID"));
    }

    @Test
    void syncJsSdkSessionReadAndEventAllVerifyTheBoundActor() throws Exception {
        when(viewer.viewerSyncContext("viewer-token"))
                .thenReturn(new ViewerSyncContextFixture().context());
        when(syncService.sync(any())).thenReturn(new WeComChatDataSyncService.SyncResult(1, 2, 0));
        WeComViewerService.SignatureBundle signature =
                new WeComViewerService.SignatureBundle("1", "nonce", "signature");
        when(viewer.jsSdkConfig("https://crm.example.com/thread", "viewer-token"))
                .thenReturn(new WeComViewerService.JsSdkConfig("corp", "agent", java.util.List.of(), signature, signature));
        when(viewer.viewerSession("session-1", "viewer-token"))
                .thenReturn(new WeComViewerService.ViewerSessionDetail(
                        "session-1", "corp", "agent", java.util.List.of()));

        mvc.perform(post("/api/v1/wecom/conversation-view/sync")
                        .header("Authorization", "Bearer crm-token")
                        .header("X-WeCom-Viewer-Token", "viewer-token"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/wecom/js-sdk-config")
                        .queryParam("url", "https://crm.example.com/thread")
                        .header("Authorization", "Bearer crm-token")
                        .header("X-WeCom-Viewer-Token", "viewer-token"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/wecom/conversation-view/sessions/session-1")
                        .header("Authorization", "Bearer crm-token")
                        .header("X-WeCom-Viewer-Token", "viewer-token"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/wecom/conversation-view/events")
                        .header("Authorization", "Bearer crm-token")
                        .header("X-WeCom-Viewer-Token", "viewer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"component_error\",\"viewerSessionId\":\"session-1\"}"))
                .andExpect(status().isNoContent());

        verify(viewer, org.mockito.Mockito.times(4)).requireViewerActor("viewer-token");
        verify(viewer).recordClientEvent("component_error", "session-1", "viewer-token");
    }

    @Test
    void componentEventAcceptsLongDedupeKeyWithinBound() throws Exception {
        String eventKey = "g".repeat(128);
        mvc.perform(post("/api/v1/wecom/conversation-view/events")
                        .header("Authorization", "Bearer crm-token")
                        .header("X-WeCom-Viewer-Token", "viewer-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"component_error\",\"eventKey\":\""
                                + eventKey + "\",\"stage\":\"frame-update\",\"generation\":1,"
                                + "\"viewerSessionId\":\"session-1\",\"errorCategory\":\"SDK_RESULT_FAILURE\"}"))
                .andExpect(status().isNoContent());
        verify(viewer).recordClientEvent("component_error", eventKey, "frame-update", 1L,
                "session-1", "SDK_RESULT_FAILURE", "viewer-token");
    }

    private static final class ViewerSyncContextFixture {
        private com.crmforlogistics.messagecenter.service.wecom.ViewerSyncContext context() {
            return new com.crmforlogistics.messagecenter.service.wecom.ViewerSyncContext(
                    "wecom-user", new com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation(
                    "installation", "suite", "corp", "agent", "ignored", 1L));
        }
    }
}
