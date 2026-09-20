package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.service.whatsapp.AdminWhatsAppAccountSyncService;
import com.crmforlogistics.messagecenter.service.whatsapp.AdminWhatsAppPhoneNumberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminWhatsAppPhoneNumberControllerTest {
    private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID OWNER = UUID.fromString("30000000-0000-0000-0000-000000000003");

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void exposesAdminAccountsRoutesWithExpectedVersion() throws Exception {
        AdminWhatsAppPhoneNumberService service = mock(AdminWhatsAppPhoneNumberService.class);
        AdminWhatsAppAccountSyncService sync = mock(AdminWhatsAppAccountSyncService.class);
        when(service.list(ACTOR)).thenReturn(List.of(new AdminWhatsAppPhoneNumberService.AccountProjection(
                ACCOUNT, OWNER, "*******1111", "销售账号", "ACTIVE", "VERIFIED", 3L)));
        when(service.reclaim(eq(ACTOR), eq(ACCOUNT), eq("收回"), eq(3L)))
                .thenReturn(new AdminWhatsAppPhoneNumberService.AccountProjection(
                        ACCOUNT, null, "*******1111", "销售账号", "ACTIVE", "VERIFIED", 4L));
        MockMvc mvc = authenticatedMvc(service, sync);

        mvc.perform(get("/api/admin/whatsapp/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].maskedPhone").value("*******1111"))
                .andExpect(jsonPath("$[0].ownerUserId").value(OWNER.toString()))
                .andExpect(jsonPath("$[0].version").value(3));

        mvc.perform(post("/api/admin/whatsapp/accounts/" + ACCOUNT + "/reclaim")
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"收回\",\"expectedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerUserId").doesNotExist())
                .andExpect(jsonPath("$.version").value(4));
        verify(service).reclaim(ACTOR, ACCOUNT, "收回", 3L);
    }

    @Test
    void syncRouteReturnsCountsAndDoesNotExposeSecrets() throws Exception {
        AdminWhatsAppPhoneNumberService service = mock(AdminWhatsAppPhoneNumberService.class);
        AdminWhatsAppAccountSyncService sync = mock(AdminWhatsAppAccountSyncService.class);
        when(sync.sync(ACTOR)).thenReturn(new AdminWhatsAppAccountSyncService.SyncResult(
                1, 2, 0, List.of(new AdminWhatsAppAccountSyncService.AccountProjection(
                        ACCOUNT, null, "*******1111", "账号", "ACTIVE", "VERIFIED", null)),
                List.of(new AdminWhatsAppAccountSyncService.ProviderPhoneReport(
                        "*******1111", "PENDING", "NOT_VERIFIED", false))));
        MockMvc mvc = authenticatedMvc(service, sync);

        mvc.perform(post("/api/admin/whatsapp/accounts/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount").value(1))
                .andExpect(jsonPath("$.refreshedCount").value(2))
                .andExpect(jsonPath("$.accounts[0].maskedPhone").value("*******1111"))
                .andExpect(jsonPath("$.accounts[0].toString").doesNotExist());
        verify(sync).sync(ACTOR);
    }

    private static MockMvc authenticatedMvc(AdminWhatsAppPhoneNumberService service,
                                            AdminWhatsAppAccountSyncService sync) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(ACTOR.toString(), "n/a", List.of()));
        return MockMvcBuilders.standaloneSetup(
                new AdminWhatsAppPhoneNumberController(service, sync)).build();
    }
}
