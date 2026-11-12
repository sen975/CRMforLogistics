package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.request.TemplateChangeReviewRequest;
import com.crmforlogistics.messagecenter.dto.response.TemplateChangeRequestResponse;
import com.crmforlogistics.messagecenter.infrastructure.SecurityUtil;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateChangeRequestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/whatsapp/template-change-requests")
public class AdminWhatsAppTemplateChangeRequestController {
    private final WhatsAppTemplateChangeRequestService changeRequests;

    public AdminWhatsAppTemplateChangeRequestController(WhatsAppTemplateChangeRequestService changeRequests) {
        this.changeRequests = changeRequests;
    }

    @GetMapping
    public TemplateChangeRequestResponse.Page list(@RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String search) {
        return changeRequests.listForReview(SecurityUtil.currentUserId(), page, size, status, search);
    }

    @PostMapping("/{requestId}/approve")
    public WhatsAppTemplateChangeRequestService.ChangeOutcome approve(@PathVariable UUID requestId,
                                                                        @RequestBody TemplateChangeReviewRequest request,
                                                                        HttpServletRequest servletRequest) {
        return changeRequests.approve(SecurityUtil.currentUserId(), requestId, request.clientRequestId(), traceId(servletRequest));
    }

    @PostMapping("/{requestId}/reject")
    public TemplateChangeRequestResponse reject(@PathVariable UUID requestId,
                                                @RequestBody TemplateChangeReviewRequest request) {
        return changeRequests.reject(SecurityUtil.currentUserId(), requestId, request.reason());
    }

    @PostMapping("/{requestId}/retry")
    public WhatsAppTemplateChangeRequestService.ChangeOutcome retry(@PathVariable UUID requestId,
                                                                      @RequestBody TemplateChangeReviewRequest request,
                                                                      HttpServletRequest servletRequest) {
        return changeRequests.retry(SecurityUtil.currentUserId(), requestId, request.clientRequestId(), traceId(servletRequest));
    }

    private static String traceId(HttpServletRequest request) {
        Object existing = request.getAttribute(WhatsAppTemplateController.TRACE_ID_ATTRIBUTE);
        if (existing instanceof String value && !value.isBlank()) return value;
        String generated = UUID.randomUUID().toString();
        request.setAttribute(WhatsAppTemplateController.TRACE_ID_ATTRIBUTE, generated);
        return generated;
    }
}
