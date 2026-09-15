package com.crmforlogistics.messagecenter.web;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WhatsAppAccountControllerTest {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("60000000-0000-0000-0000-000000000006");

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void exposesOwnerAccountProjectionAndDisablesUnlinkRoute() throws Exception {
        WhatsAppAccountLifecycleService service = mock(WhatsAppAccountLifecycleService.class);
        when(service.listMine(USER_ID)).thenReturn(List.of(new WhatsAppAccountLifecycleService.AccountProjection(
                ACCOUNT_ID, "BUSINESS_APP_COEXISTENCE", "销售账号", "备注", "*******1111",
                "ACTIVE", "VERIFIED", "PRIVATE_BUSINESS_APP", null)));
        MockMvc mvc = authenticatedMvc(service);

        mvc.perform(get("/api/whatsapp/accounts/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$[0].maskedPhone").value("*******1111"))
                .andExpect(jsonPath("$[0].templateDomain").value("PRIVATE_BUSINESS_APP"));

        mvc.perform(delete("/api/whatsapp/accounts/{accountId}", ACCOUNT_ID)
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"员工主动解绑\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));

        mvc.perform(delete("/api/whatsapp/accounts/{accountId}", ACCOUNT_ID)
                        .contentType(APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WHATSAPP_SELF_SERVICE_DISABLED"));
    }

    @Test
    void historySyncUsesOwnerAccountContext() throws Exception {
        WhatsAppAccountLifecycleService service = mock(WhatsAppAccountLifecycleService.class);
        when(service.requestHistorySync(USER_ID, ACCOUNT_ID))
                .thenReturn(new WhatsAppAccountLifecycleService.HistorySyncProjection(UUID.randomUUID(), ACCOUNT_ID, "PENDING"));
        MockMvc mvc = authenticatedMvc(service);

        mvc.perform(post("/api/whatsapp/accounts/{accountId}/history-sync", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
        verify(service).requestHistorySync(USER_ID, ACCOUNT_ID);
    }

    private static MockMvc authenticatedMvc(WhatsAppAccountLifecycleService service) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(USER_ID.toString(), "n/a", List.of()));
        return MockMvcBuilders.standaloneSetup(new WhatsAppAccountController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
