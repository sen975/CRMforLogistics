package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.CorsConfig;
import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.whatsapp.AdminWhatsAppWorkbenchService;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAdminAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminWhatsAppWorkbenchController.class)
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class})
class AdminWhatsAppWorkbenchControllerTest {
    private static final UUID SCOPE_ID = UUID.fromString("a0000000-0000-0000-0000-00000000000a");

    @Autowired MockMvc mvc;
    @MockitoBean AdminWhatsAppWorkbenchService adminWhatsAppWorkbenchService;
    @MockitoBean AuthSessionService authSessionService;
    @MockitoBean WhatsAppAdminAuthorization authorization;

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void administratorReadsTheServerOwnedOverview() throws Exception {
        when(adminWhatsAppWorkbenchService.overview()).thenReturn(new AdminWhatsAppWorkbenchService.OverviewView(
                List.of(new AdminWhatsAppWorkbenchService.ScopeOverview(
                        SCOPE_ID, "华东 CAMS", "cust-space-1", "READY", 3L, 2L,
                        Instant.parse("2026-09-15T02:00:00Z"), "SUCCEEDED", null,
                        Instant.parse("2026-09-15T03:00:00Z"), "SUCCEEDED", null)),
                2L));

        mvc.perform(get("/api/admin/whatsapp/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopes[0].scopeId").value(SCOPE_ID.toString()))
                .andExpect(jsonPath("$.scopes[0].usableAccountCount").value(3))
                .andExpect(jsonPath("$.totalPendingApprovalCount").value(2));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000004", roles = "AGENT")
    void nonAdministratorIsForbiddenBeforeTheOverviewServiceIsReached() throws Exception {
        mvc.perform(get("/api/admin/whatsapp/overview"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(adminWhatsAppWorkbenchService);
    }

    /**
     * The method above is refused by {@link SecurityConfig} before the request ever reaches the
     * controller, so it cannot see the controller's own guard. This test pins that guard directly by
     * driving the controller without the servlet filter chain, the same way
     * AdminWhatsAppScopedPhoneNumberControllerTest does.
     */
    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000004", roles = "AGENT")
    void workbenchGuardItselfRefusesTheNonAdministrator() throws Exception {
        RoleMapper roles = mock(RoleMapper.class);
        when(roles.userHasRole(UUID.fromString("00000000-0000-0000-0000-000000000004"), "admin"))
                .thenReturn(false);
        MockMvc unfiltered = MockMvcBuilders.standaloneSetup(new AdminWhatsAppWorkbenchController(
                        adminWhatsAppWorkbenchService, new WhatsAppAdminAuthorization(roles)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        unfiltered.perform(get("/api/admin/whatsapp/overview"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WHATSAPP_ADMIN_REQUIRED"));
        verifyNoInteractions(adminWhatsAppWorkbenchService);
    }
}
