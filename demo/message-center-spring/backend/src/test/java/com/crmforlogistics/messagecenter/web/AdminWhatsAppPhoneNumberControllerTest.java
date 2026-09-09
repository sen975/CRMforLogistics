package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.whatsapp.AdminWhatsAppPhoneNumberService;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppPhoneNumberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminWhatsAppPhoneNumberController.class)
@Import(SecurityConfig.class)
class AdminWhatsAppPhoneNumberControllerTest {
    private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    @Autowired MockMvc mvc;
    @MockitoBean AdminWhatsAppPhoneNumberService service;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    void agentCannotAssignWhatsAppNumber() throws Exception {
        mvc.perform(post("/api/admin/whatsapp/phone-numbers/{id}/assign", ACCOUNT_ID)
                        .with(user(ADMIN_ID.toString()).roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetOwnerId\":\"10000000-0000-0000-0000-000000000002\",\"reason\":\"handover\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanRequestAssignmentWithReason() throws Exception {
        mvc.perform(post("/api/admin/whatsapp/phone-numbers/{id}/assign", ACCOUNT_ID)
                        .with(user(ADMIN_ID.toString()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetOwnerId\":\"10000000-0000-0000-0000-000000000002\",\"reason\":\"handover\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void disableRequiresReason() throws Exception {
        mvc.perform(post("/api/admin/whatsapp/phone-numbers/{id}/disable", ACCOUNT_ID)
                        .with(user(ADMIN_ID.toString()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
