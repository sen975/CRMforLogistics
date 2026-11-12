package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.service.whatsapp.AdminWhatsAppAccountSyncService;
import com.crmforlogistics.messagecenter.service.whatsapp.AdminWhatsAppPhoneNumberService;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAdminAuthorization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminWhatsAppScopedPhoneNumberControllerTest {
    private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SCOPE = UUID.fromString("a0000000-0000-0000-0000-00000000000a");
    private static final UUID ACCOUNT = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID OWNER = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final String SECRET = "encrypted-secret";

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void exposesScopedListAndSyncRoutesThatCarryThePathScope() throws Exception {
        AdminWhatsAppPhoneNumberService service = mock(AdminWhatsAppPhoneNumberService.class);
        AdminWhatsAppAccountSyncService sync = mock(AdminWhatsAppAccountSyncService.class);
        when(service.list(ACTOR, SCOPE)).thenReturn(List.of(projection(OWNER, 3L)));
        when(sync.sync(ACTOR, SCOPE)).thenReturn(new AdminWhatsAppAccountSyncService.SyncResult(
                1, 2, 0, List.of(syncProjection()),
                List.of(new AdminWhatsAppAccountSyncService.ProviderPhoneReport(
                        "*******1111", "PENDING", "NOT_VERIFIED", false))));
        MockMvc mvc = mvc(service, sync, true);

        MvcResult listResult = mvc.perform(get("/api/admin/whatsapp/cams/" + SCOPE + "/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].maskedPhone").value("*******1111"))
                .andExpect(jsonPath("$[0].version").value(3))
                .andReturn();

        MvcResult syncResult = mvc.perform(post("/api/admin/whatsapp/cams/" + SCOPE + "/accounts/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount").value(1))
                .andExpect(jsonPath("$.accounts[0].maskedPhone").value("*******1111"))
                .andReturn();

        verify(service).list(ACTOR, SCOPE);
        verify(sync).sync(ACTOR, SCOPE);
        assertThat(listResult.getResponse().getContentAsString())
                .doesNotContain("60111111111").doesNotContain(SECRET);
        assertThat(syncResult.getResponse().getContentAsString())
                .doesNotContain("60111111111").doesNotContain(SECRET);
    }

    @Test
    void exposesScopedAssignmentRoutesThatCarryThePathScope() throws Exception {
        AdminWhatsAppPhoneNumberService service = mock(AdminWhatsAppPhoneNumberService.class);
        AdminWhatsAppAccountSyncService sync = mock(AdminWhatsAppAccountSyncService.class);
        when(service.assign(eq(ACTOR), eq(SCOPE), eq(ACCOUNT), eq(OWNER), eq("首次分配"), eq(3L)))
                .thenReturn(projection(OWNER, 4L));
        when(service.reclaim(eq(ACTOR), eq(SCOPE), eq(ACCOUNT), eq("收回"), eq(4L)))
                .thenReturn(projection(null, 5L));
        when(service.transfer(eq(ACTOR), eq(SCOPE), eq(ACCOUNT), eq(OWNER), eq("交接"), eq(5L)))
                .thenReturn(projection(OWNER, 6L));
        when(service.assignmentHistory(ACTOR, SCOPE, ACCOUNT))
                .thenReturn(List.of(new AdminWhatsAppPhoneNumberService.AssignmentAuditProjection(
                        UUID.randomUUID(), OWNER, null, ACTOR, "RECLAIM", "收回", Instant.now())));
        MockMvc mvc = mvc(service, sync, true);

        mvc.perform(post("/api/admin/whatsapp/cams/" + SCOPE + "/accounts/" + ACCOUNT + "/assign")
                        .contentType(APPLICATION_JSON)
                        .content("{\"targetOwnerId\":\"" + OWNER + "\",\"reason\":\"首次分配\",\"expectedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerUserId").value(OWNER.toString()));

        mvc.perform(post("/api/admin/whatsapp/cams/" + SCOPE + "/accounts/" + ACCOUNT + "/reclaim")
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"收回\",\"expectedVersion\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerUserId").doesNotExist());

        mvc.perform(post("/api/admin/whatsapp/cams/" + SCOPE + "/accounts/" + ACCOUNT + "/transfer")
                        .contentType(APPLICATION_JSON)
                        .content("{\"targetOwnerId\":\"" + OWNER + "\",\"reason\":\"交接\",\"expectedVersion\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(6));

        MvcResult historyResult = mvc.perform(get("/api/admin/whatsapp/cams/" + SCOPE
                        + "/accounts/" + ACCOUNT + "/assignment-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("RECLAIM"))
                .andReturn();

        verify(service).assign(ACTOR, SCOPE, ACCOUNT, OWNER, "首次分配", 3L);
        verify(service).reclaim(ACTOR, SCOPE, ACCOUNT, "收回", 4L);
        verify(service).transfer(ACTOR, SCOPE, ACCOUNT, OWNER, "交接", 5L);
        verify(service).assignmentHistory(ACTOR, SCOPE, ACCOUNT);
        assertThat(historyResult.getResponse().getContentAsString()).doesNotContain(SECRET);
    }

    @Test
    void rejectsNonAdminOnEveryScopedRoute() throws Exception {
        AdminWhatsAppPhoneNumberService service = mock(AdminWhatsAppPhoneNumberService.class);
        AdminWhatsAppAccountSyncService sync = mock(AdminWhatsAppAccountSyncService.class);
        MockMvc mvc = mvc(service, sync, false);

        for (String path : List.of("/api/admin/whatsapp/cams/" + SCOPE + "/accounts",
                "/api/admin/whatsapp/cams/" + SCOPE + "/accounts/" + ACCOUNT + "/assignment-history")) {
            mvc.perform(get(path))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("WHATSAPP_ADMIN_REQUIRED"));
        }
        mvc.perform(post("/api/admin/whatsapp/cams/" + SCOPE + "/accounts/sync"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WHATSAPP_ADMIN_REQUIRED"));
        mvc.perform(post("/api/admin/whatsapp/cams/" + SCOPE + "/accounts/" + ACCOUNT + "/assign")
                        .contentType(APPLICATION_JSON)
                        .content("{\"targetOwnerId\":\"" + OWNER + "\",\"reason\":\"首次分配\",\"expectedVersion\":3}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WHATSAPP_ADMIN_REQUIRED"));
        mvc.perform(post("/api/admin/whatsapp/cams/" + SCOPE + "/accounts/" + ACCOUNT + "/reclaim")
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"收回\",\"expectedVersion\":4}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WHATSAPP_ADMIN_REQUIRED"));
        mvc.perform(post("/api/admin/whatsapp/cams/" + SCOPE + "/accounts/" + ACCOUNT + "/transfer")
                        .contentType(APPLICATION_JSON)
                        .content("{\"targetOwnerId\":\"" + OWNER + "\",\"reason\":\"交接\",\"expectedVersion\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WHATSAPP_ADMIN_REQUIRED"));

        verifyNoInteractions(service, sync);
    }

    private static MockMvc mvc(AdminWhatsAppPhoneNumberService service,
                               AdminWhatsAppAccountSyncService sync,
                               boolean admin) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(ACTOR.toString(), "n/a", List.of()));
        RoleMapper roles = mock(RoleMapper.class);
        when(roles.userHasRole(ACTOR, "admin")).thenReturn(admin);
        return MockMvcBuilders.standaloneSetup(new AdminWhatsAppScopedPhoneNumberController(
                        service, sync, new WhatsAppAdminAuthorization(roles)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static AdminWhatsAppPhoneNumberService.AccountProjection projection(UUID owner, long version) {
        return new AdminWhatsAppPhoneNumberService.AccountProjection(
                ACCOUNT, owner, "*******1111", "销售账号", "ACTIVE", "VERIFIED", version);
    }

    private static AdminWhatsAppAccountSyncService.AccountProjection syncProjection() {
        return new AdminWhatsAppAccountSyncService.AccountProjection(
                ACCOUNT, null, "*******1111", "账号", "ACTIVE", "VERIFIED", Instant.now());
    }
}
