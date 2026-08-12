package com.crmforlogistics.messagecenter.web;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.crmforlogistics.messagecenter.config.CorsConfig;
import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.AuditLogMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMediaAssetMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateOperationMapper;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppTemplateService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService.OperationHistoryView;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService.OperationView;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService.TemplatePageView;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService.TemplateView;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateException;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateGateway;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.ComponentType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.MediaAssetStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.OperationStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.OperationType;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.TemplateComponent;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateReconciliationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService.MediaAssetView;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService.UploadResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WebMvcTest({WhatsAppTemplateController.class, TemplateController.class})
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class})
class WhatsAppTemplateControllerTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String ADMIN_ID_TEXT = "00000000-0000-0000-0000-000000000002";
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TEMPLATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID OPERATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");
    private static final String BASE = "/api/v1/channel-accounts/" + ACCOUNT_ID + "/whatsapp";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean WhatsAppTemplateApplicationService templateService;
    @MockitoBean WhatsAppTemplateReconciliationService reconciliationService;
    @MockitoBean WhatsAppTemplateMediaUploadService mediaUploadService;
    @MockitoBean ChatAppTemplateService salesTemplateService;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    void anonymousCannotAccessManagementApi() throws Exception {
        for (RequestBuilder request : managementRequests()) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000005", roles = "AGENT")
    void salesCannotInvokeAnyManagementWritePath() throws Exception {
        for (RequestBuilder request : managementWriteRequests()) {
            mvc.perform(request).andExpect(status().isForbidden());
        }

        verify(templateService, never()).create(any(), any(), any(), any());
        verify(templateService, never()).modify(any(), any(), any(), any(), any(), any());
        verify(templateService, never()).setSendPermission(any(), any(), any(), anyBoolean(), any(), any(), any());
        verify(templateService, never()).delete(any(), any(), any(), any(), any(), any());
        verify(mediaUploadService, never()).upload(any(), any(), any(), anyLong(), any(), any(), any(), any(), any());
        verify(mediaUploadService, never()).find(any(), any());
        verify(reconciliationService, never()).syncAccount(any());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000005", roles = "AGENT")
    void salesCannotInvokeAnyManagementReadPath() throws Exception {
        for (RequestBuilder request : managementReadRequests()) {
            mvc.perform(request).andExpect(status().isForbidden());
        }
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000005", roles = "AGENT")
    void salesCanStillAccessSendableTemplateSelector() throws Exception {
        when(salesTemplateService.listAll()).thenReturn(List.of());

        mvc.perform(get("/api/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(username = ADMIN_ID_TEXT, roles = "ADMIN")
    void adminCanInvokeEveryManagementWritePath() throws Exception {
        OperationView operation = new OperationView(OPERATION_ID, OperationType.CREATE,
                OperationStatus.SUCCEEDED, "delivery_update", "req-1", null);
        when(templateService.create(eq(ACCOUNT_ID), any(), eq(ADMIN_ID), any())).thenReturn(operation);
        when(templateService.modify(eq(ACCOUNT_ID), eq("delivery_update"), eq("en_US"),
                any(), eq(ADMIN_ID), any())).thenReturn(operation);
        when(templateService.setSendPermission(eq(ACCOUNT_ID), eq("delivery_update"), eq("en_US"),
                eq(false), eq("permission-1"), eq(ADMIN_ID), any())).thenReturn(operation);
        when(templateService.delete(eq(ACCOUNT_ID), eq("delivery_update"), eq("en_US"),
                eq("delete-1"), eq(ADMIN_ID), any())).thenReturn(operation);
        when(mediaUploadService.upload(eq(ACCOUNT_ID), eq(HeaderFormat.IMAGE), any(), eq(3L),
                eq("header.png"), eq("image/png"), eq("upload-management"), eq(ADMIN_ID), any()))
                .thenReturn(new UploadResult(uploadedView("upload-management", MediaAssetStatus.UPLOADED), true));
        when(reconciliationService.syncAccount(ACCOUNT_ID))
                .thenReturn(new WhatsAppTemplateReconciliationService.SyncResult(1, 1, 1, true));

        List<RequestBuilder> requests = managementWriteRequests();
        for (int index = 0; index < requests.size(); index++) {
            var action = mvc.perform(requests.get(index)).andExpect(status().is2xxSuccessful());
            if (index < 4) {
                action.andExpect(jsonPath("$.traceId").isNotEmpty());
            }
        }

        verify(templateService).validateAccount(ACCOUNT_ID);
        verify(reconciliationService).syncAccount(ACCOUNT_ID);
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void mapsListFiltersAndReturnsPagedProjection() throws Exception {
        when(templateService.list(ACCOUNT_ID, 2, 25, "delivery", "REJECTED", "UTILITY",
                "en_US", false, true))
                .thenReturn(new TemplatePageView(List.of(templateView()), 1, 2, 25));

        mvc.perform(get("/api/v1/channel-accounts/{accountId}/whatsapp/templates", ACCOUNT_ID)
                        .param("page", "2")
                        .param("size", "25")
                        .param("search", "delivery")
                        .param("status", "REJECTED")
                        .param("category", "UTILITY")
                        .param("language", "en_US")
                        .param("allowSend", "false")
                        .param("deleted", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].templateCode").value("delivery_update"))
                .andExpect(jsonPath("$.items[0].rejectionReason").value("BODY_NOT_ALLOWED"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(25));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void returnsDetailAndOperationHistory() throws Exception {
        when(templateService.detail(ACCOUNT_ID, "delivery_update", "en_US")).thenReturn(templateView());
        when(templateService.history(ACCOUNT_ID, "delivery_update", "en_US"))
                .thenReturn(List.of(new OperationHistoryView(OPERATION_ID, "MODIFY", "FAILED",
                        "delivery_update", "en_US", "provider-1", "PROVIDER_REJECTED", "rejected",
                        "trace-1", ADMIN_ID, NOW, NOW)));

        mvc.perform(get("/api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}",
                        ACCOUNT_ID, "delivery_update").param("language", "en_US"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[0].type").value("BODY"));

        mvc.perform(get("/api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}/operations",
                        ACCOUNT_ID, "delivery_update").param("language", "en_US"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].operationId").value(OPERATION_ID.toString()))
                .andExpect(jsonPath("$[0].errorCode").value("PROVIDER_REJECTED"))
                .andExpect(jsonPath("$[0].providerRequestId").doesNotExist());
    }

    @Test
    @WithMockUser(username = ADMIN_ID_TEXT, roles = "ADMIN")
    void uploadRequiresRequestIdAndReturnsCreatedForNewSuccess() throws Exception {
        MediaAssetView asset = uploadedView("upload-1", MediaAssetStatus.UPLOADED);
        when(mediaUploadService.upload(eq(ACCOUNT_ID), eq(HeaderFormat.IMAGE), any(), eq(4L),
                eq("header.png"), eq("image/png"), eq("upload-1"), eq(ADMIN_ID), any()))
                .thenReturn(new UploadResult(asset, true));

        mvc.perform(multipart(BASE + "/template-media")
                        .file(new MockMultipartFile("file", "header.png", "image/png", new byte[]{1, 2, 3, 4}))
                        .param("format", "IMAGE")
                        .param("clientRequestId", "upload-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientRequestId").value("upload-1"))
                .andExpect(jsonPath("$.assetStatus").value("UPLOADED"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID_TEXT, roles = "ADMIN")
    void processingUploadReturnsAcceptedAndCanBeQueried() throws Exception {
        MediaAssetView processing = uploadedView("upload-2", MediaAssetStatus.PROCESSING);
        when(mediaUploadService.upload(any(), any(), any(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(new UploadResult(processing, false));
        when(mediaUploadService.find(ACCOUNT_ID, "upload-2")).thenReturn(processing);

        mvc.perform(multipart(BASE + "/template-media")
                        .file(new MockMultipartFile("file", "header.png", "image/png", new byte[]{1}))
                        .param("format", "IMAGE")
                        .param("clientRequestId", "upload-2"))
                .andExpect(status().isAccepted());
        mvc.perform(get(BASE + "/template-media/uploads/upload-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetStatus").value("PROCESSING"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID_TEXT, roles = "ADMIN")
    void replayedUploadedMediaReturnsOk() throws Exception {
        MediaAssetView uploaded = uploadedView("upload-replay", MediaAssetStatus.UPLOADED);
        when(mediaUploadService.upload(any(), any(), any(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(new UploadResult(uploaded, false));

        mvc.perform(multipart(BASE + "/template-media")
                        .file(new MockMultipartFile("file", "header.png", "image/png", new byte[]{1}))
                        .param("format", "IMAGE")
                        .param("clientRequestId", "upload-replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetStatus").value("UPLOADED"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID_TEXT, roles = "ADMIN")
    void submissionUnknownUploadReturnsAccepted() throws Exception {
        MediaAssetView unknown = uploadedView("upload-unknown", MediaAssetStatus.SUBMISSION_UNKNOWN);
        when(mediaUploadService.upload(any(), any(), any(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(new UploadResult(unknown, true));

        mvc.perform(multipart(BASE + "/template-media")
                        .file(new MockMultipartFile("file", "header.png", "image/png", new byte[]{1}))
                        .param("format", "IMAGE")
                        .param("clientRequestId", "upload-unknown"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.assetStatus").value("SUBMISSION_UNKNOWN"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID_TEXT, roles = "ADMIN")
    void missingUploadRequestIdReturnsBadRequest() throws Exception {
        mvc.perform(multipart(BASE + "/template-media")
                        .file(new MockMultipartFile("file", "header.png", "image/png", new byte[]{1}))
                        .param("format", "IMAGE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verify(mediaUploadService, never()).upload(any(), any(), any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    @WithMockUser(username = ADMIN_ID_TEXT, roles = "ADMIN")
    void missingUploadCanBeQueriedAsStableNotFound() throws Exception {
        when(mediaUploadService.find(ACCOUNT_ID, "missing"))
                .thenThrow(new WhatsAppTemplateException("TEMPLATE_MEDIA_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "Media upload was not found", Map.of(), null, false));

        mvc.perform(get(BASE + "/template-media/uploads/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEMPLATE_MEDIA_NOT_FOUND"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID_TEXT, roles = "ADMIN")
    void reusedRequestIdWithDifferentFingerprintReturnsConflict() throws Exception {
        when(mediaUploadService.upload(any(), any(), any(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new WhatsAppTemplateException("IDEMPOTENCY_KEY_REUSED", HttpStatus.CONFLICT,
                        "clientRequestId is already bound to different media", Map.of(), null, false));

        mvc.perform(multipart(BASE + "/template-media")
                        .file(new MockMultipartFile("file", "header.png", "image/png", new byte[]{1}))
                        .param("format", "IMAGE")
                        .param("clientRequestId", "upload-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @WithMockUser(username = ADMIN_ID_TEXT, roles = "ADMIN")
    void syncRemainsReadOnlyWithoutClientRequestId() throws Exception {
        when(reconciliationService.syncAccount(ACCOUNT_ID))
                .thenReturn(new WhatsAppTemplateReconciliationService.SyncResult(1, 1, 1, true));

        mvc.perform(post(BASE + "/templates/sync"))
                .andExpect(status().isOk());

        verify(templateService).validateAccount(ACCOUNT_ID);
        verify(reconciliationService).syncAccount(ACCOUNT_ID);
        verify(mediaUploadService, never()).upload(any(), any(), any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void mapsTemplateExceptionToStableApiError() throws Exception {
        when(templateService.create(eq(ACCOUNT_ID), any(), eq(ADMIN_ID), any()))
                .thenThrow(WhatsAppTemplateException.validation(Map.of("components", "BODY is required")));

        MvcResult result = mvc.perform(createRequest())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEMPLATE_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("WhatsApp template validation failed"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.components").value("BODY is required"))
                .andReturn();

        var traceId = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(templateService).create(eq(ACCOUNT_ID), any(), eq(ADMIN_ID), traceId.capture());
        assertEquals(traceId.getValue(), objectMapper.readTree(result.getResponse().getContentAsString())
                .get("traceId").asText());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void requestBindingErrorsAreStableBadRequests() throws Exception {
        mvc.perform(get("/api/v1/channel-accounts/not-a-uuid/whatsapp/templates"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").isMap());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void unexpectedErrorsDoNotLeakInternalExceptionMessages() throws Exception {
        when(templateService.detail(ACCOUNT_ID, "delivery_update", "en_US"))
                .thenThrow(new IllegalStateException("database-password=secret"));

        mvc.perform(get("/api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}",
                        ACCOUNT_ID, "delivery_update").param("language", "en_US"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    @Test
    void queryOwnerUsesAnExplicitBoundedWindowWithoutPaginationPlugin() {
        ChannelAccountMapper accountMapper = mock(ChannelAccountMapper.class);
        TemplateOperationMapper operationMapper = mock(TemplateOperationMapper.class);
        TemplateMediaAssetMapper mediaMapper = mock(TemplateMediaAssetMapper.class);
        TemplateMapper mapper = mock(TemplateMapper.class);
        AuditLogMapper auditMapper = mock(AuditLogMapper.class);
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(ACCOUNT_ID);
        account.setChannelType("whatsapp");
        account.setAuthStatus("active");
        when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(account);
        when(mapper.selectCount(any())).thenReturn(51L);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.<TemplateEntity>of());
        WhatsAppTemplateApplicationService owner = new WhatsAppTemplateApplicationService(
                accountMapper, operationMapper, mediaMapper, mapper, auditMapper,
                mock(WhatsAppTemplateGateway.class), mock(WhatsAppTemplateValidator.class),
                new ObjectMapper(), Clock.systemUTC());

        TemplatePageView page = owner.list(ACCOUNT_ID, 2, 25, null, null,
                null, null, null, null);

        assertEquals(51, page.total());
        var countQuery = org.mockito.ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectCount(countQuery.capture());
        assertTrue(!countQuery.getValue().getCustomSqlSegment().contains("ORDER BY"));
        var query = org.mockito.ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectList(query.capture());
        assertTrue(query.getValue().getCustomSqlSegment().contains("ORDER BY updated_at DESC"));
        assertTrue(query.getValue().getCustomSqlSegment().contains("LIMIT 25 OFFSET 25"));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void missingSendPermissionValueReturnsFieldError() throws Exception {
        mvc.perform(put("/api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}/send-permission",
                        ACCOUNT_ID, "delivery_update")
                        .param("language", "en_US")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientRequestId\":\"permission-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEMPLATE_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.allowSend").value("is required"));
    }

    private static List<RequestBuilder> managementRequests() {
        List<RequestBuilder> requests = new java.util.ArrayList<>();
        requests.addAll(managementReadRequests());
        requests.addAll(managementWriteRequests());
        return requests;
    }

    private static List<RequestBuilder> managementReadRequests() {
        return List.of(
                get("/api/v1/channel-accounts/{accountId}/whatsapp/templates", ACCOUNT_ID),
                get("/api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}",
                        ACCOUNT_ID, "delivery_update").param("language", "en_US"),
                get("/api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}/operations",
                        ACCOUNT_ID, "delivery_update").param("language", "en_US"),
                get(BASE + "/template-media/uploads/upload-security"));
    }

    private static List<RequestBuilder> managementWriteRequests() {
        return List.of(
                createRequest(),
                put("/api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}",
                        ACCOUNT_ID, "delivery_update")
                        .param("language", "en_US")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson()),
                put("/api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}/send-permission",
                        ACCOUNT_ID, "delivery_update")
                        .param("language", "en_US")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowSend\":false,\"clientRequestId\":\"permission-1\"}"),
                delete("/api/v1/channel-accounts/{accountId}/whatsapp/templates/{templateCode}",
                        ACCOUNT_ID, "delivery_update")
                        .param("language", "en_US")
                        .param("clientRequestId", "delete-1"),
                post("/api/v1/channel-accounts/{accountId}/whatsapp/templates/sync", ACCOUNT_ID),
                multipart("/api/v1/channel-accounts/{accountId}/whatsapp/template-media", ACCOUNT_ID)
                        .file(new MockMultipartFile("file", "header.png", "image/png", new byte[]{1, 2, 3}))
                        .param("format", "IMAGE")
                        .param("clientRequestId", "upload-management"));
    }

    private static RequestBuilder createRequest() {
        return post("/api/v1/channel-accounts/{accountId}/whatsapp/templates", ACCOUNT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createJson());
    }

    private static String createJson() {
        return "{\"name\":\"delivery\",\"language\":\"en_US\",\"category\":\"UTILITY\","
                + "\"components\":[{\"type\":\"BODY\",\"text\":\"Hello {{customer}}\"}],"
                + "\"examples\":{\"customer\":[\"Ada\"]},\"clientRequestId\":\"create-1\"}";
    }

    private static String updateJson() {
        return "{\"name\":\"delivery\",\"category\":\"UTILITY\","
                + "\"components\":[{\"type\":\"BODY\",\"text\":\"Updated {{customer}}\"}],"
                + "\"examples\":{\"customer\":[\"Ada\"]},\"clientRequestId\":\"modify-1\"}";
    }

    private static TemplateView templateView() {
        return new TemplateView(TEMPLATE_ID, ACCOUNT_ID, "delivery_update", "delivery", "en_US", "UTILITY",
                "REJECTED", "fail", "BODY_NOT_ALLOWED", false,
                List.of(new TemplateComponent(ComponentType.BODY, null, "Hello {{customer}}", null, List.of())),
                Map.of("customer", List.of("Ada")), null, "GREEN", NOW, NOW, null);
    }

    private static MediaAssetView uploadedView(String requestId, MediaAssetStatus status) {
        return new MediaAssetView(UUID.randomUUID(), requestId, HeaderFormat.IMAGE, "image/png", 4,
                "0".repeat(64), status == MediaAssetStatus.PROCESSING ? null
                        : "https://provider.invalid/header.png",
                status, null, null, "trace-1");
    }
}
