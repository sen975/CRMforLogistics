package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.CorsConfig;
import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.dto.response.TemplateChangeRequestResponse;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateChangeRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminWhatsAppTemplateChangeRequestController.class)
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class})
class AdminWhatsAppTemplateChangeRequestControllerTest {
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Autowired MockMvc mvc;
    @MockitoBean WhatsAppTemplateChangeRequestService changeRequestService;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void administratorListsAndApprovesPendingChangeRequests() throws Exception {
        when(changeRequestService.listForReview(ADMIN_ID, 1, 20))
                .thenReturn(new TemplateChangeRequestResponse.Page(List.of(view("PENDING_APPROVAL")), 1, 1, 20));
        when(changeRequestService.approve(ADMIN_ID, REQUEST_ID, "review-1", "trace-1"))
                .thenReturn(new WhatsAppTemplateChangeRequestService.ChangeOutcome(null, view("SUCCEEDED"), null));

        mvc.perform(get("/api/v1/admin/whatsapp/template-change-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("PENDING_APPROVAL"));
        mvc.perform(post("/api/v1/admin/whatsapp/template-change-requests/{requestId}/approve", REQUEST_ID)
                        .contentType("application/json")
                        .requestAttr(WhatsAppTemplateController.TRACE_ID_ATTRIBUTE, "trace-1")
                        .content("{\"clientRequestId\":\"review-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.status").value("SUCCEEDED"));

        verify(changeRequestService).approve(ADMIN_ID, REQUEST_ID, "review-1", "trace-1");
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000004", roles = "AGENT")
    void nonAdministratorIsForbiddenBeforeTheApprovalServiceIsCalled() throws Exception {
        mvc.perform(post("/api/v1/admin/whatsapp/template-change-requests/{requestId}/approve", REQUEST_ID)
                        .contentType("application/json").content("{\"clientRequestId\":\"review-2\"}"))
                .andExpect(status().isForbidden());
    }

    private static TemplateChangeRequestResponse view(String status) {
        return new TemplateChangeRequestResponse(REQUEST_ID, UUID.randomUUID(), "模板（template）", 2,
                "SET_SEND_PERMISSION", status, List.of(), "申请人", null, null,
                null, null, "provider-1", Instant.EPOCH, null, null);
    }
}
