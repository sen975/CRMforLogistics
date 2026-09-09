package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.request.TemplateCreateRequest;
import com.crmforlogistics.messagecenter.dto.request.TemplateChangeCommandRequest;
import com.crmforlogistics.messagecenter.dto.request.TemplateSendPermissionRequest;
import com.crmforlogistics.messagecenter.dto.request.TemplateUpdateRequest;
import com.crmforlogistics.messagecenter.dto.response.SharedTemplateResponse;
import com.crmforlogistics.messagecenter.dto.response.TemplateChangeRequestResponse;
import com.crmforlogistics.messagecenter.dto.response.TemplateOperationResponse;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateApplicationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppProviderScopeService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppSharedTemplateCatalogService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateChangeRequestService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateException;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService.MediaAssetView;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService.UploadResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.MediaAssetStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateReconciliationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateScopeGate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/whatsapp")
public class WhatsAppTemplateController {
    static final String TRACE_ID_ATTRIBUTE = WhatsAppTemplateController.class.getName() + ".traceId";

    private final WhatsAppSharedTemplateCatalogService catalogService;
    private final WhatsAppTemplateApplicationService templateApplicationService;
    private final WhatsAppTemplateChangeRequestService changeRequestService;
    private final PublicTemplateApplicationService publicTemplateService;
    private final WhatsAppTemplateReconciliationService reconciliationService;
    private final WhatsAppTemplateMediaUploadService mediaUploadService;
    private final WhatsAppProviderScopeService providerScopeService;
    private final WhatsAppTemplateScopeGate scopeGate;

    public WhatsAppTemplateController(WhatsAppSharedTemplateCatalogService catalogService,
                                      WhatsAppTemplateApplicationService templateApplicationService,
                                      WhatsAppTemplateChangeRequestService changeRequestService,
                                      PublicTemplateApplicationService publicTemplateService,
                                      WhatsAppTemplateReconciliationService reconciliationService,
                                      WhatsAppTemplateMediaUploadService mediaUploadService,
                                      WhatsAppProviderScopeService providerScopeService,
                                      WhatsAppTemplateScopeGate scopeGate) {
        this.catalogService = catalogService;
        this.templateApplicationService = templateApplicationService;
        this.changeRequestService = changeRequestService;
        this.publicTemplateService = publicTemplateService;
        this.reconciliationService = reconciliationService;
        this.mediaUploadService = mediaUploadService;
        this.providerScopeService = providerScopeService;
        this.scopeGate = scopeGate;
    }

    @GetMapping("/templates")
    public SharedTemplateResponse.Page list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) Boolean allowSend,
            @RequestParam(required = false) Boolean deleted) {
        scopeGate.requireReady();
        return catalogService.list(actorUserId(), page, size,
                new WhatsAppSharedTemplateCatalogService.TemplateFilters(
                        search, status, category, language, allowSend, deleted));
    }

    @GetMapping("/templates/{templateId}")
    public SharedTemplateResponse detail(@PathVariable UUID templateId) {
        scopeGate.requireReady();
        return catalogService.detail(actorUserId(), templateId);
    }

    @PostMapping("/templates/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateOperationResponse create(@RequestBody TemplateCreateRequest request,
                                            HttpServletRequest servletRequest) {
        UUID actorUserId = actorUserId();
        WhatsAppProviderScopeService.ScopeAccount scopeAccount = requireCurrentScopeAccount(actorUserId);
        String traceId = traceId(servletRequest);
        return TemplateOperationResponse.from(templateApplicationService.create(scopeAccount.scope().getId(),
                scopeAccount.account().getId(), request.toCommand(), actorUserId, traceId), traceId);
    }

    @PostMapping("/templates/sync")
    public WhatsAppTemplateReconciliationService.SyncResult sync() {
        UUID actorUserId = actorUserId();
        WhatsAppProviderScopeService.ScopeAccount scopeAccount = requireCurrentScopeAccount(actorUserId);
        return reconciliationService.syncScope(scopeAccount.scope().getId(), scopeAccount.account().getId());
    }

    @GetMapping("/business-app/accounts/{accountId}/templates")
    public SharedTemplateResponse.Page listPrivate(@PathVariable UUID accountId,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size,
                                                     @RequestParam(required = false) String search,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String category,
                                                     @RequestParam(required = false) String language,
                                                     @RequestParam(required = false) Boolean allowSend,
                                                     @RequestParam(required = false) Boolean deleted) {
        UUID actor = actorUserId();
        templateApplicationService.requireOwnedBusinessAppAccount(accountId, actor);
        return catalogService.listPrivate(actor, accountId, page, size,
                new WhatsAppSharedTemplateCatalogService.TemplateFilters(
                        search, status, category, language, allowSend, deleted));
    }

    @GetMapping("/business-app/accounts/{accountId}/templates/{templateId}")
    public SharedTemplateResponse privateDetail(@PathVariable UUID accountId, @PathVariable UUID templateId) {
        UUID actor = actorUserId();
        templateApplicationService.requireOwnedBusinessAppAccount(accountId, actor);
        return catalogService.privateDetail(actor, accountId, templateId);
    }

    @PostMapping("/business-app/accounts/{accountId}/templates/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateOperationResponse createPrivate(@PathVariable UUID accountId,
                                                    @RequestBody TemplateCreateRequest request,
                                                    HttpServletRequest servletRequest) {
        UUID actor = actorUserId();
        templateApplicationService.requireOwnedBusinessAppAccount(accountId, actor);
        return TemplateOperationResponse.from(templateApplicationService.createPrivate(accountId,
                request.toCommand(), actor, traceId(servletRequest)), traceId(servletRequest));
    }

    @PutMapping("/business-app/accounts/{accountId}/templates/{templateId}")
    public TemplateOperationResponse modifyPrivate(@PathVariable UUID accountId, @PathVariable UUID templateId,
                                                    @RequestBody TemplateUpdateRequest request,
                                                    HttpServletRequest servletRequest) {
        UUID actor = actorUserId();
        templateApplicationService.requireOwnedBusinessAppAccount(accountId, actor);
        return TemplateOperationResponse.from(templateApplicationService.modifyPrivate(accountId, actor, templateId,
                request.toCommand(null), traceId(servletRequest)), traceId(servletRequest));
    }

    @PatchMapping("/business-app/accounts/{accountId}/templates/{templateId}/send-permission")
    public TemplateOperationResponse setPrivateSendPermission(@PathVariable UUID accountId,
                                                               @PathVariable UUID templateId,
                                                               @RequestBody TemplateSendPermissionRequest request,
                                                               HttpServletRequest servletRequest) {
        UUID actor = actorUserId();
        templateApplicationService.requireOwnedBusinessAppAccount(accountId, actor);
        return TemplateOperationResponse.from(templateApplicationService.setSendPermissionPrivate(accountId, actor,
                templateId, Boolean.TRUE.equals(request.allowSend()), request.clientRequestId(),
                traceId(servletRequest)), traceId(servletRequest));
    }

    @DeleteMapping("/business-app/accounts/{accountId}/templates/{templateId}")
    public TemplateOperationResponse deletePrivate(@PathVariable UUID accountId, @PathVariable UUID templateId,
                                                   @RequestParam String clientRequestId,
                                                   HttpServletRequest servletRequest) {
        UUID actor = actorUserId();
        templateApplicationService.requireOwnedBusinessAppAccount(accountId, actor);
        return TemplateOperationResponse.from(templateApplicationService.deletePrivate(accountId, actor, templateId,
                clientRequestId, traceId(servletRequest)), traceId(servletRequest));
    }

    @PostMapping("/business-app/accounts/{accountId}/templates/sync")
    public WhatsAppTemplateReconciliationService.SyncResult syncPrivate(@PathVariable UUID accountId) {
        UUID actor = actorUserId();
        templateApplicationService.requireOwnedBusinessAppAccount(accountId, actor);
        return reconciliationService.syncPrivateAccount(accountId);
    }

    @GetMapping("/business-app/accounts/{accountId}/templates/{templateId}/operations")
    public List<TemplateOperationResponse> privateHistory(@PathVariable UUID accountId,
                                                           @PathVariable UUID templateId) {
        UUID actor = actorUserId();
        templateApplicationService.requireOwnedBusinessAppAccount(accountId, actor);
        return catalogService.privateHistory(actor, accountId, templateId).stream()
                .map(TemplateOperationResponse::from).toList();
    }

    @PostMapping("/templates/{templateId}/change-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public WhatsAppTemplateChangeRequestService.ChangeOutcome submitChangeRequest(
            @PathVariable UUID templateId, @RequestBody TemplateChangeCommandRequest request,
            HttpServletRequest servletRequest) {
        scopeGate.requireReady();
        return changeRequestService.submit(actorUserId(), templateId, request.toCommand(), traceId(servletRequest));
    }

    @GetMapping("/template-change-requests/mine")
    public TemplateChangeRequestResponse.Page listMyChangeRequests(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        scopeGate.requireReady();
        return changeRequestService.listMine(actorUserId(), page, size);
    }

    @GetMapping("/templates/{templateId}/operations")
    public List<TemplateOperationResponse> history(@PathVariable UUID templateId) {
        scopeGate.requireReady();
        return catalogService.history(actorUserId(), templateId).stream()
                .map(TemplateOperationResponse::from)
                .toList();
    }

    @GetMapping("/public-templates")
    public PublicTemplateModels.Page listPublicTemplates(
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "zh_CN") String language,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) List<String> industries,
            @RequestParam(required = false) List<String> usecases,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        scopeGate.requireReady();
        return publicTemplateService.listForUser(actorUserId(),
                new PublicTemplateModels.Query(name, language, category, industries, usecases, page, size));
    }

    @PostMapping("/template-media")
    public ResponseEntity<MediaAssetView> uploadMedia(@RequestParam HeaderFormat format,
                                                       @RequestParam String clientRequestId,
                                                       @RequestParam("file") MultipartFile file,
                                                       HttpServletRequest servletRequest) throws IOException {
        scopeGate.requireReady();
        UUID actorUserId = actorUserId();
        String traceId = traceId(servletRequest);
        String fileName = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        try (InputStream input = file.getInputStream()) {
            UploadResult result = mediaUploadService.uploadForUser(actorUserId, format, input, file.getSize(),
                    fileName, file.getContentType(), clientRequestId, traceId);
            return ResponseEntity.status(uploadStatus(result)).body(result.asset());
        }
    }

    @GetMapping("/template-media/uploads/{clientRequestId}")
    public MediaAssetView findMediaUpload(@PathVariable String clientRequestId) {
        scopeGate.requireReady();
        return mediaUploadService.findForUser(actorUserId(), clientRequestId);
    }

    private WhatsAppProviderScopeService.ScopeAccount requireCurrentScopeAccount(UUID actorUserId) {
        UUID readyScopeId = scopeGate.requireReady();
        WhatsAppProviderScopeService.ScopeAccount scopeAccount = providerScopeService.requireOwnedActive(actorUserId);
        if (!readyScopeId.equals(scopeAccount.scope().getId())) {
            throw new WhatsAppTemplateException("WHATSAPP_PROVIDER_SCOPE_MISMATCH", HttpStatus.CONFLICT,
                    "WhatsApp account does not belong to the shared template scope", Map.of(), null, false);
        }
        return scopeAccount;
    }

    private static HttpStatus uploadStatus(UploadResult result) {
        if (result.created() && result.asset().assetStatus() == MediaAssetStatus.UPLOADED) {
            return HttpStatus.CREATED;
        }
        if (result.asset().assetStatus() == MediaAssetStatus.PROCESSING
                || result.asset().assetStatus() == MediaAssetStatus.SUBMISSION_UNKNOWN) {
            return HttpStatus.ACCEPTED;
        }
        return HttpStatus.OK;
    }

    private static UUID actorUserId() {
        return SecurityUtil.currentUserId();
    }

    private static String traceId(HttpServletRequest request) {
        Object existing = request.getAttribute(TRACE_ID_ATTRIBUTE);
        if (existing instanceof String value && !value.isBlank()) {
            return value;
        }
        String generated = UUID.randomUUID().toString();
        request.setAttribute(TRACE_ID_ATTRIBUTE, generated);
        return generated;
    }
}
