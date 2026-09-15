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

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
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
    void authenticatedUserReceivesSelfServiceDisabledForAuthorizationRoutes() throws Exception {
        UUID attemptId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        mvc.perform(post("/api/whatsapp/authorization/complete")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"20000000-0000-0000-0000-000000000002\","
                                + "\"state\":\"AbCdEfGhIjKlMnOpQrStUvWxYz012345\","
                                + "\"event\":\"FINISH\",\"wabaId\":\"waba-1\","
                                + "\"phoneNumberId\":\"phone-id-1\",\"code\":\"meta-code\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        mvc.perform(post("/api/whatsapp/authorization/complete/{attemptId}/phone", attemptId)
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"AbCdEfGhIjKlMnOpQrStUvWxYz012345\","
                                + "\"candidateId\":\"AbCdEfGhIjKlMnOpQrStUvWxYz012345\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        mvc.perform(post("/api/whatsapp/authorization/attempts")
                        .with(user(USER_ID.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onboardingMode\":\"EMPLOYEE_BUSINESS_APP\","
                                + "\"accountName\":\"销售账号\",\"accountRemark\":\"东南亚客户\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        verifyNoInteractions(service);
    }
}
