package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAuthorizationService;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppPhoneNumberService;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAccountLifecycleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WhatsAppSelfServiceDisabledControllerTest {
    private static final UUID ATTEMPT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OPERATION_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String STATE = "AbCdEfGhIjKlMnOpQrStUvWxYz012345";

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void oldSelfServiceRoutesAreGoneWithoutCallingAuthorizationOrPhoneServices() throws Exception {
        WhatsAppAuthorizationService authorizations = mock(WhatsAppAuthorizationService.class);
        WhatsAppPhoneNumberService phoneNumbers = mock(WhatsAppPhoneNumberService.class);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "30000000-0000-0000-0000-000000000003", "n/a", List.of()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new WhatsAppAuthorizationController(authorizations),
                        new WhatsAppPhoneNumberController(phoneNumbers))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(post("/api/whatsapp/authorization/attempts")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"onboardingMode":"ADMIN_API_WABA","accountName":"旧账号"}
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        mvc.perform(post("/api/whatsapp/authorization/complete")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        mvc.perform(post("/api/whatsapp/authorization/complete")
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        mvc.perform(post("/api/whatsapp/authorization/complete")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"attemptId":"10000000-0000-0000-0000-000000000001",
                                 "state":"AbCdEfGhIjKlMnOpQrStUvWxYz012345","event":"FINISH",
                                 "wabaId":"waba-1","phoneNumberId":"phone-1","code":"code"}
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        mvc.perform(post("/api/whatsapp/authorization/complete/not-a-uuid/phone")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        mvc.perform(post("/api/whatsapp/authorization/complete/{attemptId}/phone", ATTEMPT_ID)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"state":"AbCdEfGhIjKlMnOpQrStUvWxYz012345",
                                 "candidateId":"AbCdEfGhIjKlMnOpQrStUvWxYz012345"}
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        mvc.perform(post("/api/whatsapp/api-phone-operations")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"countryCode":"60","phoneNumber":"+60111111111",
                                 "verifiedName":"旧账号","accountName":"旧账号"}
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        mvc.perform(post("/api/whatsapp/api-phone-operations")
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        mvc.perform(post("/api/whatsapp/api-phone-operations/{operationId}/verification-code", OPERATION_ID)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"locale":"zh_CN","method":"sms","confirmed":true}
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        mvc.perform(post("/api/whatsapp/api-phone-operations/{operationId}/verify", OPERATION_ID)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"verificationCode":"123456"}
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        mvc.perform(post("/api/whatsapp/api-phone-operations/not-a-uuid/verify")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        mvc.perform(get("/api/whatsapp/api-phone-operations"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        verifyNoInteractions(authorizations, phoneNumbers);
    }

    @Test
    void employeeCannotSelfUnlinkManagedAccount() throws Exception {
        WhatsAppAccountLifecycleService lifecycle = mock(WhatsAppAccountLifecycleService.class);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "30000000-0000-0000-0000-000000000003", "n/a", List.of()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new WhatsAppAccountController(lifecycle))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(delete("/api/whatsapp/accounts/not-a-uuid")
                        .contentType(APPLICATION_JSON))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        verifyNoInteractions(lifecycle);
    }
}
