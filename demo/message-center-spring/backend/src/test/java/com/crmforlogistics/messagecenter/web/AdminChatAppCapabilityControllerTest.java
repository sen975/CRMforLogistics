package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.dto.response.ChatAppCapabilityReport;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.channel.ChatAppCapabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminChatAppCapabilityController.class)
@Import(SecurityConfig.class)
class AdminChatAppCapabilityControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ChatAppCapabilityService service;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    @WithMockUser(roles = "AGENT")
    void salesUserCannotRunCapabilityProbe() throws Exception {
        mvc.perform(post("/api/admin/chatapp/capabilities/read-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"00000000-0000-0000-0000-000000000011\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanRunReadOnlyProbe() throws Exception {
        when(service.probeReadOnly(any())).thenReturn(report(true));

        mvc.perform(post("/api/admin/chatapp/capabilities/read-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"00000000-0000-0000-0000-000000000011\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("READ_ONLY"))
                .andExpect(jsonPath("$.ready").value(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void provisioningRequiresExplicitConfirmationHeader() throws Exception {
        mvc.perform(post("/api/admin/chatapp/capabilities/add-number")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"00000000-0000-0000-0000-000000000011",
                                 "countryCode":"60","phoneNumber":"60122222222","verifiedName":"CRM Test"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void latestReportIsAvailableWithoutSecrets() throws Exception {
        when(service.latest()).thenReturn(report(false));

        mvc.perform(get("/api/admin/chatapp/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].diagnosticMessage").value("permission denied"));
    }

    private static ChatAppCapabilityReport report(boolean ready) {
        return new ChatAppCapabilityReport(UUID.randomUUID(), null, null, "READ_ONLY", ready,
                Instant.parse("2026-09-08T03:00:00Z"), List.of(new ChatAppCapabilityReport.ActionResult(
                "QueryChatappBindWaba", ready ? "VERIFIED" : "FAILED", "request-1",
                ready ? "OK" : "Forbidden", ready ? "SUCCESS" : "permission denied")));
    }
}
