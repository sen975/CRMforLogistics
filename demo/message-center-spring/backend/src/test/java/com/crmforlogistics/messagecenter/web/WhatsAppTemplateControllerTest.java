package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.CorsConfig;
import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.dto.response.SharedTemplateResponse;
import com.crmforlogistics.messagecenter.dto.response.TemplateChangeRequestResponse;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateApplicationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.Page;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppProviderScopeService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppSharedTemplateCatalogService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateChangeRequestService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateException;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.MediaAssetStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.OperationStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.OperationType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateReconciliationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateScopeGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WhatsAppTemplateController.class)
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class})
class WhatsAppTemplateControllerTest {
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID SCOPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID TEMPLATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");

    @Autowired MockMvc mvc;
    @MockitoBean WhatsAppSharedTemplateCatalogService catalogService;
    @MockitoBean WhatsAppTemplateApplicationService templateApplicationService;
    @MockitoBean WhatsAppTemplateChangeRequestService changeRequestService;
    @MockitoBean PublicTemplateApplicationService publicTemplateService;
    @MockitoBean WhatsAppTemplateReconciliationService reconciliationService;
    @MockitoBean WhatsAppTemplateMediaUploadService mediaUploadService;
    @MockitoBean WhatsAppProviderScopeService providerScopeService;
    @MockitoBean WhatsAppTemplateScopeGate scopeGate;
    @MockitoBean AuthSessionService authSessionService;

    @BeforeEach
    void setUp() {
        when(scopeGate.requireReady()).thenReturn(SCOPE_ID);
        when(providerScopeService.requireOwnedActive(any())).thenReturn(scopeAccount());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000003", roles = "AGENT")
    void agentReadsTheSharedCatalogWithoutAnAccountAuthorizationCheck() throws Exception {
        when(catalogService.list(eq(AGENT_ID), eq(1), eq(20), any())).thenReturn(page());
        when(catalogService.detail(AGENT_ID, TEMPLATE_ID)).thenReturn(template());

        mvc.perform(get("/api/v1/whatsapp/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(TEMPLATE_ID.toString()))
                .andExpect(jsonPath("$.items[0].version").value(3))
                .andExpect(jsonPath("$.items[0].accountId").doesNotExist())
                .andExpect(jsonPath("$.items[0].providerScopeId").doesNotExist());
        mvc.perform(get("/api/v1/whatsapp/templates/{templateId}", TEMPLATE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").doesNotExist());

        verify(providerScopeService, never()).requireOwnedActive(AGENT_ID);
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000003", roles = "AGENT")
    void agentCanCreateSyncBrowsePublicTemplatesAndManageOwnMedia() throws Exception {
        stubInteractiveEndpoints(AGENT_ID);

        mvc.perform(post("/api/v1/whatsapp/templates/applications")
                        .contentType(MediaType.APPLICATION_JSON).content(createJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.operationType").value("CREATE"));
        mvc.perform(post("/api/v1/whatsapp/templates/sync")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/whatsapp/public-templates")).andExpect(status().isOk());
        mvc.perform(multipart("/api/v1/whatsapp/template-media")
                        .file(new MockMultipartFile("file", "header.png", "image/png", new byte[]{1, 2, 3}))
                        .param("format", "IMAGE").param("clientRequestId", "upload-1"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/whatsapp/template-media/uploads/upload-1"))
                .andExpect(status().isOk());

        verify(templateApplicationService).create(eq(SCOPE_ID), eq(ACCOUNT_ID), any(), eq(AGENT_ID), any());
        verify(reconciliationService).syncScope(SCOPE_ID, ACCOUNT_ID);
        verify(publicTemplateService).listForUser(eq(AGENT_ID), any());
        verify(mediaUploadService).uploadForUser(eq(AGENT_ID), eq(HeaderFormat.IMAGE), any(), eq(3L),
                eq("header.png"), eq("image/png"), eq("upload-1"), any());
        verify(mediaUploadService).findForUser(AGENT_ID, "upload-1");
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void adminCanUseTheSameSharedTemplateRoutes() throws Exception {
        when(catalogService.list(eq(ADMIN_ID), eq(1), eq(20), any())).thenReturn(page());
        when(catalogService.detail(ADMIN_ID, TEMPLATE_ID)).thenReturn(template());
        stubInteractiveEndpoints(ADMIN_ID);

        mvc.perform(get("/api/v1/whatsapp/templates")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/whatsapp/templates/{templateId}", TEMPLATE_ID)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/whatsapp/templates/applications")
                .contentType(MediaType.APPLICATION_JSON).content(createJson())).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/whatsapp/templates/sync")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/whatsapp/public-templates")).andExpect(status().isOk());
        mvc.perform(multipart("/api/v1/whatsapp/template-media")
                .file(new MockMultipartFile("file", "header.png", "image/png", new byte[]{1, 2, 3}))
                .param("format", "IMAGE").param("clientRequestId", "upload-1")).andExpect(status().isCreated());
    }

    @Test
    void sharedTemplateRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/whatsapp/templates")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000003", roles = "AGENT")
    void closedMigrationGateReturnsServiceUnavailable() throws Exception {
        when(scopeGate.requireReady()).thenThrow(new WhatsAppTemplateException(
                "WHATSAPP_TEMPLATE_MIGRATION_PENDING", HttpStatus.SERVICE_UNAVAILABLE,
                "共享 WhatsApp 模板正在初始化", Map.of(), null, true));

        mvc.perform(get("/api/v1/whatsapp/templates"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WHATSAPP_TEMPLATE_MIGRATION_PENDING"));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000003", roles = "AGENT")
    void legacyAccountScopedTemplateRouteIsNotMapped() throws Exception {
        mvc.perform(get("/api/v1/channel-accounts/{accountId}/whatsapp/templates", ACCOUNT_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000003", roles = "AGENT")
    void agentSubmitsAndListsOnlyOwnTemplateChangeRequests() throws Exception {
        when(changeRequestService.submit(eq(AGENT_ID), eq(TEMPLATE_ID), any(), any()))
                .thenReturn(changeOutcome());
        when(changeRequestService.listMine(AGENT_ID, 1, 20)).thenReturn(changePage());

        mvc.perform(post("/api/v1/whatsapp/templates/{templateId}/change-requests", TEMPLATE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(changeJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.request.status").value("PENDING_APPROVAL"));
        mvc.perform(get("/api/v1/whatsapp/template-change-requests/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].templateId").value(TEMPLATE_ID.toString()));

        verify(changeRequestService).submit(eq(AGENT_ID), eq(TEMPLATE_ID), any(), any());
        verify(changeRequestService).listMine(AGENT_ID, 1, 20);
    }

    private void stubInteractiveEndpoints(UUID actorId) {
        when(templateApplicationService.create(eq(SCOPE_ID), eq(ACCOUNT_ID), any(), eq(actorId), any()))
                .thenReturn(operation());
        when(reconciliationService.syncScope(SCOPE_ID, ACCOUNT_ID))
                .thenReturn(new WhatsAppTemplateReconciliationService.SyncResult(1, 1, 1, true));
        when(publicTemplateService.listForUser(eq(actorId), any())).thenReturn(new Page(List.of(), 0, 1, 20));
        WhatsAppTemplateMediaUploadService.MediaAssetView asset = media("upload-1");
        when(mediaUploadService.uploadForUser(eq(actorId), eq(HeaderFormat.IMAGE), any(), eq(3L),
                eq("header.png"), eq("image/png"), eq("upload-1"), any()))
                .thenReturn(new WhatsAppTemplateMediaUploadService.UploadResult(asset, true));
        when(mediaUploadService.findForUser(actorId, "upload-1")).thenReturn(asset);
    }

    private static WhatsAppProviderScopeService.ScopeAccount scopeAccount() {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(ACCOUNT_ID);
        WhatsAppProviderScopeEntity scope = new WhatsAppProviderScopeEntity();
        scope.setId(SCOPE_ID);
        return new WhatsAppProviderScopeService.ScopeAccount(account, scope);
    }

    private static SharedTemplateResponse.Page page() {
        return new SharedTemplateResponse.Page(List.of(template()), 1, 1, 20);
    }

    private static SharedTemplateResponse template() {
        return new SharedTemplateResponse(TEMPLATE_ID, 3, "shipping_notice", "shipping_notice", "发货提醒",
                "发货提醒（shipping_notice）", "zh_CN", "UTILITY", "APPROVED", "APPROVED", null,
                true, List.of(new TemplateComponent(ComponentType.BODY, null, "Hello $(customer)", null, List.of())),
                Map.of("customer", List.of("Ada")), null, "GREEN", Instant.EPOCH, Instant.EPOCH, null);
    }

    private static WhatsAppTemplateApplicationService.OperationView operation() {
        return new WhatsAppTemplateApplicationService.OperationView(UUID.randomUUID(), OperationType.CREATE,
                OperationStatus.SUCCEEDED, "shipping_notice", "provider-1", null);
    }

    private static WhatsAppTemplateMediaUploadService.MediaAssetView media(String requestId) {
        return new WhatsAppTemplateMediaUploadService.MediaAssetView(UUID.randomUUID(), requestId,
                HeaderFormat.IMAGE, "image/png", 3, "0".repeat(64), "https://provider.invalid/header.png",
                MediaAssetStatus.UPLOADED, null, null, "trace-1");
    }

    private static WhatsAppTemplateChangeRequestService.ChangeOutcome changeOutcome() {
        return new WhatsAppTemplateChangeRequestService.ChangeOutcome(
                WhatsAppTemplateModels.ChangeMode.APPROVAL_REQUIRED, changeView(), null);
    }

    private static TemplateChangeRequestResponse.Page changePage() {
        return new TemplateChangeRequestResponse.Page(List.of(changeView()), 1, 1, 20);
    }

    private static TemplateChangeRequestResponse changeView() {
        return new TemplateChangeRequestResponse(UUID.fromString("00000000-0000-0000-0000-000000000007"), TEMPLATE_ID,
                "发货提醒（shipping_notice）", 3, "SET_SEND_PERMISSION", "PENDING_APPROVAL", List.of(),
                "申请人", null, null, null, null, null, Instant.EPOCH, null, null);
    }

    private static String changeJson() {
        return "{\"changeType\":\"SET_SEND_PERMISSION\",\"expectedVersion\":3,"
                + "\"clientRequestId\":\"change-1\",\"allowSend\":false}";
    }

    private static String createJson() {
        return "{\"name\":\"shipping_notice\",\"language\":\"zh_CN\",\"category\":\"UTILITY\","
                + "\"components\":[{\"type\":\"BODY\",\"text\":\"Hello $(customer)\"}],"
                + "\"examples\":{\"customer\":[\"Ada\"]},\"clientRequestId\":\"create-1\"}";
    }
}
