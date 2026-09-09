package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WhatsAppAuthorizationController.class)
@Import(SecurityConfig.class)
class WhatsAppAuthorizationControllerTest {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    @Autowired MockMvc mvc;
    @MockitoBean WhatsAppAuthorizationService service;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    void unauthenticatedUserCannotCreateAttempt() throws Exception {
        mvc.perform(post("/api/whatsapp/authorization/attempts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserReceivesOneTimeAttempt() throws Exception {
        when(service.createAttempt(eq(USER_ID), eq("EMPLOYEE_BUSINESS_APP"), any(), any())).thenReturn(
                new WhatsAppAuthorizationService.AttemptProjection(UUID.randomUUID(),
                        "state-abcdefghijklmnopqrstuvwxyz123456", Instant.parse("2026-09-09T03:05:00Z"),
                        new com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppOnboardingGateway.StartupProfile(
                                "cams-app", "cams-config", "EMPLOYEE_BUSINESS_APP")));

        mvc.perform(post("/api/whatsapp/authorization/attempts")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onboardingMode\":\"EMPLOYEE_BUSINESS_APP\","
                                + "\"accountName\":\"销售账号\",\"accountRemark\":\"东南亚客户\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("state-abcdefghijklmnopqrstuvwxyz123456"))
                .andExpect(jsonPath("$.startupProfile.appId").value("cams-app"));
        verify(service).createAttempt(USER_ID, "EMPLOYEE_BUSINESS_APP", "销售账号", "东南亚客户");
    }

    @Test
    void completionDoesNotAcceptCredentials() throws Exception {
        when(service.completeAuthorization(eq(USER_ID), any())).thenReturn(
                new WhatsAppAuthorizationService.CompletionProjection(UUID.randomUUID(), "1111",
                        "BUSINESS_APP_COEXISTENCE"));
        mvc.perform(post("/api/whatsapp/authorization/complete")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"20000000-0000-0000-0000-000000000002\","
                                + "\"state\":\"state-abcdefghijklmnopqrstuvwxyz123456\","
                                + "\"event\":\"FINISH\",\"wabaId\":\"waba-1\","
                                + "\"phoneNumberId\":\"phone-id-1\",\"code\":\"meta-code\","
                                + "\"accessKeySecret\":\"must-not-be-accepted\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumberLast4").value("1111"));
        verify(service).completeAuthorization(eq(USER_ID), any());
    }

    @Test
    void phoneSelectionUsesAttemptBoundCandidateToken() throws Exception {
        UUID attemptId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        when(service.selectBusinessAppPhone(eq(USER_ID), any())).thenReturn(
                new WhatsAppAuthorizationService.CompletionProjection(UUID.randomUUID(), "2222",
                        "EMPLOYEE_BUSINESS_APP"));

        mvc.perform(post("/api/whatsapp/authorization/complete/{attemptId}/phone", attemptId)
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"state-abcdefghijklmnopqrstuvwxyz123456\","
                                + "\"candidateId\":\"opaque-candidate-token-abcdefghijklmnopqrstuvwxyz123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumberLast4").value("2222"));
        verify(service).selectBusinessAppPhone(eq(USER_ID), any());
    }
}
