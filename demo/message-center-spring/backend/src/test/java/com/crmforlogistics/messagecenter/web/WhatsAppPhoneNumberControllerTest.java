package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppPhoneNumberService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WhatsAppPhoneNumberControllerTest {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OPERATION_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void oldApiPhoneOperationsRoutesAreDisabled() throws Exception {
        WhatsAppPhoneNumberService service = mock(WhatsAppPhoneNumberService.class);
        MockMvc mvc = authenticatedMvc(service);

        mvc.perform(get("/api/whatsapp/api-phone-operations"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        mvc.perform(post("/api/whatsapp/api-phone-operations")
                        .contentType(APPLICATION_JSON)
                        .content("{\"countryCode\":\"60\",\"phoneNumber\":\"+60111111111\","
                                + "\"verifiedName\":\"Provider Name\",\"accountName\":\"销售账号\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        mvc.perform(post("/api/whatsapp/api-phone-operations/{operationId}/verification-code", OPERATION_ID)
                        .contentType(APPLICATION_JSON)
                        .content("{\"locale\":\"zh_CN\",\"method\":\"sms\",\"confirmed\":true}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        mvc.perform(post("/api/whatsapp/api-phone-operations/{operationId}/verify", OPERATION_ID)
                        .contentType(APPLICATION_JSON)
                        .content("{\"verificationCode\":\"123456\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        verifyNoInteractions(service);
    }

    private static MockMvc authenticatedMvc(WhatsAppPhoneNumberService service) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(USER_ID.toString(), "n/a", List.of()));
        return MockMvcBuilders.standaloneSetup(new WhatsAppPhoneNumberController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
