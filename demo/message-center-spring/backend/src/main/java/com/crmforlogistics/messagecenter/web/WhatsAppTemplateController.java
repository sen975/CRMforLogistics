package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.request.TemplateCreateRequest;
import com.crmforlogistics.messagecenter.dto.request.TemplateChangeCommandRequest;
import com.crmforlogistics.messagecenter.dto.request.TemplateSendPermissionRequest;
import com.crmforlogistics.messagecenter.dto.request.TemplateUpdateRequest;
import com.crmforlogistics.messagecenter.dto.response.SharedTemplateResponse;
import com.crmforlogistics.messagecenter.dto.response.TemplateChangeRequestResponse;
import com.crmforlogistics.messagecenter.dto.response.TemplateOperationResponse;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.whatsapp.WhatsAppAdminAuthorization;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateApplicationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppProviderScopeService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppSharedTemplateCatalogService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateChangeRequestService;
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
    private final WhatsAppAdminAuthorization adminAuthorization;
    private final WhatsAppTemplateScopeGate scopeGate;

    public WhatsAppTemplateController(WhatsAppSharedTemplateCatalogService catalogService,
                                      WhatsAppTemplateApplicationService templateApplicationService,
                                      WhatsAppTemplateChangeRequestService changeRequestService,
                                      PublicTemplateApplicationService publicTemplateService,
                                      WhatsAppTemplateReconciliationService reconciliationService,
                                      WhatsAppTemplateMediaUploadService mediaUploadService,
                                      WhatsAppProviderScopeService providerScopeService,
                                      WhatsAppAdminAuthorization adminAuthorization,
                                      WhatsAppTemplateScopeGate scopeGate) {
        this.catalogService = catalogService;
        this.templateApplicationService = templateApplicationService;
        this.changeRequestService = changeRequestService;
        this.publicTemplateService = publicTemplateService;
        this.reconciliationService = reconciliationService;
        this.mediaUploadService = mediaUploadService;
        this.providerScopeService = providerScopeService;
        this.adminAuthorization = adminAuthorization;
        this.scopeGate = scopeGate;
    }

    @GetMapping("/templates")
    public SharedTemplateResponse.Page list(
            @RequestParam(required = false) UUID scopeId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) Boolean allowSend,
            @RequestParam(required = false) Boolean deleted) {
        return catalogService.list(resolveTemplateScope(actorUserId(), scopeId), page, size,
                new WhatsAppSharedTemplateCatalogService.TemplateFilters(
                        search, status, category, language, allowSend, deleted));
    }

    @GetMapping("/templates/{templateId}")
    public SharedTemplateResponse detail(@PathVariable UUID templateId,
                                         @RequestParam(required = false) UUID scopeId) {
        return catalogService.detail(resolveTemplateScope(actorUserId(), scopeId), templateId);
    }

    @PostMapping("/templates/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateOperationResponse create(@RequestBody TemplateCreateRequest request,
                                            @RequestParam(required = false) UUID scopeId,
                                            HttpServletRequest servletRequest) {
        UUID actorUserId = actorUserId();
        WhatsAppProviderScopeService.ScopeAccount scopeAccount = resolveWriteScopeAccount(actorUserId, scopeId);
        String traceId = traceId(servletRequest);
        return TemplateOperationResponse.from(templateApplicationService.create(scopeAccount.scope().getId(),
                scopeAccount.account().getId(), request.toCommand(), actorUserId, traceId), traceId);
    }

    @PostMapping("/templates/sync")
    public WhatsAppTemplateReconciliationService.SyncResult sync(@RequestParam(required = false) UUID scopeId) {
        UUID actorUserId = actorUserId();
        WhatsAppProviderScopeService.ScopeAccount scopeAccount = resolveWriteScopeAccount(actorUserId, scopeId);
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
            @RequestParam(required = false) UUID scopeId, HttpServletRequest servletRequest) {
        UUID actorUserId = actorUserId();
        WhatsAppProviderScopeService.ScopeAccount scopeAccount = resolveWriteScopeAccount(actorUserId, scopeId);
        return changeRequestService.submit(actorUserId, templateId, request.toCommand(), scopeAccount,
                traceId(servletRequest));
    }

    @GetMapping("/template-change-requests/mine")
    public TemplateChangeRequestResponse.Page listMyChangeRequests(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        scopeGate.requireReady();
        return changeRequestService.listMine(actorUserId(), page, size);
    }

    @GetMapping("/templates/{templateId}/operations")
    public List<TemplateOperationResponse> history(@PathVariable UUID templateId,
                                                    @RequestParam(required = false) UUID scopeId) {
        return catalogService.history(resolveTemplateScope(actorUserId(), scopeId), templateId).stream()
                .map(TemplateOperationResponse::from)
                .toList();
    }

    @GetMapping("/public-templates")
    public PublicTemplateModels.Page listPublicTemplates(
            @RequestParam(required = false) UUID scopeId,
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "zh_CN") String language,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) List<String> industries,
            @RequestParam(required = false) List<String> usecases,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID actorUserId = actorUserId();
        return publicTemplateService.list(resolveTemplateScope(actorUserId, scopeId),
                new PublicTemplateModels.Query(name, language, category, industries, usecases, page, size));
    }

    @PostMapping("/template-media")
    public ResponseEntity<MediaAssetView> uploadMedia(@RequestParam HeaderFormat format,
                                                       @RequestParam String clientRequestId,
                                                       @RequestParam(required = false) UUID scopeId,
                                                       @RequestParam("file") MultipartFile file,
                                                       HttpServletRequest servletRequest) throws IOException {
        UUID actorUserId = actorUserId();
        WhatsAppProviderScopeService.ScopeAccount scopeAccount = resolveWriteScopeAccount(actorUserId, scopeId);
        String traceId = traceId(servletRequest);
        String fileName = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        try (InputStream input = file.getInputStream()) {
            UploadResult result = mediaUploadService.uploadForUser(actorUserId, scopeAccount, format, input,
                    file.getSize(), fileName, file.getContentType(), clientRequestId, traceId);
            return ResponseEntity.status(uploadStatus(result)).body(result.asset());
        }
    }

    @GetMapping("/template-media/uploads/{clientRequestId}")
    public MediaAssetView findMediaUpload(@PathVariable String clientRequestId,
                                          @RequestParam(required = false) UUID scopeId) {
        UUID actorUserId = actorUserId();
        return mediaUploadService.findForUser(resolveWriteScopeAccount(actorUserId, scopeId), clientRequestId);
    }

    /**
     * Each CAMS space keeps its own template library, so a reader must name the space they mean.
     * Only an administrator may read a space other than the one the migration gate opened; for
     * everyone else an explicit {@code scopeId} is refused rather than silently ignored.
     */
    private UUID resolveTemplateScope(UUID actorUserId, UUID requestedScopeId) {
        if (requestedScopeId == null) {
            return scopeGate.requireReady();
        }
        adminAuthorization.requireAdmin(actorUserId);
        return providerScopeService.requireScope(requestedScopeId).getId();
    }

    /**
     * A write — and the lookup of the media it produced — has to land in the space the caller names,
     * or a library they are looking at is not the one they are writing into. Naming a space other
     * than the default is an administrator action, exactly as reading one is, so both go through the
     * same gate.
     */
    private WhatsAppProviderScopeService.ScopeAccount resolveWriteScopeAccount(UUID actorUserId,
                                                                               UUID requestedScopeId) {
        if (requestedScopeId == null) {
            return requireCurrentScopeAccount(actorUserId);
        }
        adminAuthorization.requireAdmin(actorUserId);
        return providerScopeService.requireScopeAccount(actorUserId, requestedScopeId);
    }

    /**
     * 写动作落在<b>调用者自己账号所在的那个空间</b> —— 这正是 {@code requireOwnedActive} 做的事：
     * 它解密账号凭证、取出 CAMS 的 {@code custSpaceId}、把空间写回账号（{@code bind}）。
     *
     * <h2>为什么不再和迁移门比对（2026-09-29）</h2>
     * 这里以前是「{@code scopeGate.requireReady()} 的空间 == 账号的空间，否则
     * {@code WHATSAPP_PROVIDER_SCOPE_MISMATCH}」。那个等式把<b>已经算出来的答案</b>丢掉了：
     * 空间本来就由账号凭证决定，而迁移门记的是迁移当时的那<b>一个</b>空间。
     * 于是「一个在本系统里有 chatapp 账号、凭证也齐备的用户」只要所属空间不是迁移门那一个，
     * 就在登录之后连一张图都传不上去 —— 而错误信息里没有一个字提示这一点。
     *
     * <p>这同时回答了「非管理员不带 {@code scopeId} 时怎么确定是哪个 CAMS 空间的模板」：
     * 不需要猜，也不需要迁移门批准 —— 账号绑的是哪个空间，写就落在哪个空间。
     * 只有「没有 chatapp 账号」（{@code WHATSAPP_ACCOUNT_REQUIRED}）与
     * 「多个账号跨空间、无从取舍」（{@code WHATSAPP_ACCOUNT_AMBIGUOUS}）才仍然需要
     * 调用方给出 {@code scopeId}，而那是管理员的路径。
     *
     * <p>迁移门仍在读路径上用（默认读哪个空间的那份清单），本路径不再需要它。
     */
    private WhatsAppProviderScopeService.ScopeAccount requireCurrentScopeAccount(UUID actorUserId) {
        return providerScopeService.requireOwnedActive(actorUserId);
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
