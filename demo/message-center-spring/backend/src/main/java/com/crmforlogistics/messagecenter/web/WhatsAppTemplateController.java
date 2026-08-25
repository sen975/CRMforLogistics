package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.request.TemplateCreateRequest;
import com.crmforlogistics.messagecenter.dto.request.TemplateRemarkRequest;
import com.crmforlogistics.messagecenter.dto.request.TemplateSendPermissionRequest;
import com.crmforlogistics.messagecenter.dto.request.TemplateUpdateRequest;
import com.crmforlogistics.messagecenter.dto.response.TemplateAdminResponse;
import com.crmforlogistics.messagecenter.dto.response.TemplateOperationResponse;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateApplicationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService.MediaAssetView;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService.UploadResult;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.HeaderFormat;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateModels.MediaAssetStatus;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateReconciliationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateRemarkService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/channel-accounts/{accountId}/whatsapp")
public class WhatsAppTemplateController {
    static final String TRACE_ID_ATTRIBUTE = WhatsAppTemplateController.class.getName() + ".traceId";

    private final WhatsAppTemplateApplicationService templateService;
    private final PublicTemplateApplicationService publicTemplateService;
    private final WhatsAppTemplateReconciliationService reconciliationService;
    private final WhatsAppTemplateMediaUploadService mediaUploadService;
    private final WhatsAppTemplateRemarkService remarkService;

    public WhatsAppTemplateController(WhatsAppTemplateApplicationService templateService,
                                      PublicTemplateApplicationService publicTemplateService,
                                      WhatsAppTemplateReconciliationService reconciliationService,
                                      WhatsAppTemplateMediaUploadService mediaUploadService,
                                      WhatsAppTemplateRemarkService remarkService) {
        this.templateService = templateService;
        this.publicTemplateService = publicTemplateService;
        this.reconciliationService = reconciliationService;
        this.mediaUploadService = mediaUploadService;
        this.remarkService = remarkService;
    }

    @GetMapping("/templates")
    public TemplateAdminResponse.Page list(
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) Boolean allowSend,
            @RequestParam(required = false) Boolean deleted) {
        return TemplateAdminResponse.Page.from(templateService.list(accountId, page, size, search,
                status, category, language, allowSend, deleted));
    }

    @GetMapping("/public-templates")
    public PublicTemplateModels.Page listPublicTemplates(
            @PathVariable UUID accountId,
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "zh_CN") String language,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) List<String> industries,
            @RequestParam(required = false) List<String> usecases,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return publicTemplateService.list(accountId,
                new PublicTemplateModels.Query(name, language, category, industries, usecases, page, size));
    }

    @GetMapping("/templates/{templateCode}")
    public TemplateAdminResponse detail(@PathVariable UUID accountId,
                                        @PathVariable String templateCode,
                                        @RequestParam String language) {
        return TemplateAdminResponse.from(templateService.detail(accountId, templateCode, language));
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateOperationResponse create(@PathVariable UUID accountId,
                                            @RequestBody TemplateCreateRequest request,
                                            Authentication authentication,
                                            HttpServletRequest servletRequest) {
        String traceId = traceId(servletRequest);
        return TemplateOperationResponse.from(templateService.create(accountId, request.toCommand(),
                actorUserId(authentication), traceId), traceId);
    }

    @PutMapping("/templates/{templateCode}")
    public TemplateOperationResponse modify(@PathVariable UUID accountId,
                                            @PathVariable String templateCode,
                                            @RequestParam String language,
                                            @RequestBody TemplateUpdateRequest request,
                                            Authentication authentication,
                                            HttpServletRequest servletRequest) {
        String traceId = traceId(servletRequest);
        return TemplateOperationResponse.from(templateService.modify(accountId, templateCode, language,
                request.toCommand(language), actorUserId(authentication), traceId), traceId);
    }

    @PutMapping("/templates/{templateCode}/remark")
    public TemplateAdminResponse updateRemark(@PathVariable UUID accountId,
                                              @PathVariable String templateCode,
                                              @RequestParam String language,
                                              @RequestBody TemplateRemarkRequest request,
                                              Authentication authentication,
                                              HttpServletRequest servletRequest) {
        String traceId = traceId(servletRequest);
        return TemplateAdminResponse.from(remarkService.update(accountId, templateCode, language, request.remark(),
                actorUserId(authentication), traceId));
    }

    @PutMapping("/templates/{templateCode}/send-permission")
    public TemplateOperationResponse setSendPermission(@PathVariable UUID accountId,
                                                       @PathVariable String templateCode,
                                                       @RequestParam String language,
                                                       @Valid @RequestBody TemplateSendPermissionRequest request,
                                                       Authentication authentication,
                                                       HttpServletRequest servletRequest) {
        String traceId = traceId(servletRequest);
        return TemplateOperationResponse.from(templateService.setSendPermission(accountId, templateCode, language,
                request.allowSend(), request.clientRequestId(), actorUserId(authentication), traceId), traceId);
    }

    @DeleteMapping("/templates/{templateCode}")
    public TemplateOperationResponse delete(@PathVariable UUID accountId,
                                            @PathVariable String templateCode,
                                            @RequestParam String language,
                                            @RequestParam String clientRequestId,
                                            Authentication authentication,
                                            HttpServletRequest servletRequest) {
        String traceId = traceId(servletRequest);
        return TemplateOperationResponse.from(templateService.delete(accountId, templateCode, language,
                clientRequestId, actorUserId(authentication), traceId), traceId);
    }

    @PostMapping("/templates/sync")
    public WhatsAppTemplateReconciliationService.SyncResult sync(@PathVariable UUID accountId,
                                                                  HttpServletRequest servletRequest) {
        traceId(servletRequest);
        templateService.validateAccount(accountId);
        return reconciliationService.syncAccount(accountId);
    }

    @PostMapping("/template-media")
    public ResponseEntity<MediaAssetView> uploadMedia(
            @PathVariable UUID accountId,
            @RequestParam HeaderFormat format,
            @RequestParam String clientRequestId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication,
            HttpServletRequest servletRequest) throws IOException {
        String traceId = traceId(servletRequest);
        String fileName = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        try (InputStream input = file.getInputStream()) {
            UploadResult result = mediaUploadService.upload(accountId, format, input, file.getSize(), fileName,
                    file.getContentType(), clientRequestId, actorUserId(authentication), traceId);
            HttpStatus status = result.created() && result.asset().assetStatus() == MediaAssetStatus.UPLOADED
                    ? HttpStatus.CREATED
                    : result.asset().assetStatus() == MediaAssetStatus.PROCESSING
                        || result.asset().assetStatus() == MediaAssetStatus.SUBMISSION_UNKNOWN
                            ? HttpStatus.ACCEPTED : HttpStatus.OK;
            return ResponseEntity.status(status).body(result.asset());
        }
    }

    @GetMapping("/template-media/uploads/{clientRequestId}")
    public MediaAssetView findMediaUpload(@PathVariable UUID accountId,
                                          @PathVariable String clientRequestId) {
        return mediaUploadService.find(accountId, clientRequestId);
    }

    @GetMapping("/templates/{templateCode}/operations")
    public List<TemplateOperationResponse> history(@PathVariable UUID accountId,
                                                   @PathVariable String templateCode,
                                                   @RequestParam String language) {
        return templateService.history(accountId, templateCode, language).stream()
                .map(TemplateOperationResponse::from)
                .toList();
    }

    private static UUID actorUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new SecurityException("Authenticated user ID is unavailable");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException error) {
            throw new SecurityException("Authenticated user ID is invalid");
        }
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
