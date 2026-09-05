package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.CorsConfig;
import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateApplicationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.Button;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.Content;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.MessagePage;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.Page;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.PublicTemplate;
import com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.Query;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateApplicationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateException;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateMediaUploadService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateReconciliationService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppProviderScopeService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppSharedTemplateCatalogService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateScopeGate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WhatsAppTemplateController.class)
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class})
class PublicTemplateControllerTest {
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired MockMvc mvc;
    @MockitoBean WhatsAppTemplateApplicationService templateService;
    @MockitoBean WhatsAppTemplateReconciliationService reconciliationService;
    @MockitoBean WhatsAppTemplateMediaUploadService mediaUploadService;
    @MockitoBean PublicTemplateApplicationService publicTemplateService;
    @MockitoBean WhatsAppSharedTemplateCatalogService catalogService;
    @MockitoBean WhatsAppProviderScopeService providerScopeService;
    @MockitoBean WhatsAppTemplateScopeGate scopeGate;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void adminListsSharedPublicTemplatesWithFilters() throws Exception {
        Query query = new Query("shipping", "en_US", "UTILITY", List.of("logistics"),
                List.of("ORDER_MANAGEMENT"), 2, 10);
        when(publicTemplateService.listForUser(ADMIN_ID, query)).thenReturn(new Page(List.of(
                new PublicTemplate("code-1", "shipping", "en_US", "UTILITY", List.of("logistics"),
                        "ORDER_MANAGEMENT", null, new Content("shipping", null, "external-1", "en_US",
                        "UTILITY", List.of(new MessagePage("page1", "Hello $(name)", List.of(
                        new Button("Open", "visitWebsite", "https://example.com")))), List.of()))), 1, 2, 10));

        mvc.perform(get("/api/v1/whatsapp/public-templates")
                        .param("name", "shipping")
                        .param("language", "en_US")
                        .param("category", "UTILITY")
                        .param("industries", "logistics")
                        .param("usecases", "ORDER_MANAGEMENT")
                        .param("page", "2")
                        .param("size", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.items[0].content.pages[0].name").value("page1"))
                .andExpect(jsonPath("$.items[0].content.pages[0].text").value("Hello $(name)"))
                .andExpect(jsonPath("$.items[0].content.pages[0].buttons[0].url").value("https://example.com"))
                .andExpect(jsonPath("$.items[0].content.buttons").doesNotExist());

        verify(publicTemplateService).listForUser(eq(ADMIN_ID), eq(query));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000005", roles = "AGENT")
    void agentCanAccessSharedPublicTemplateLibrary() throws Exception {
        UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000005");
        when(publicTemplateService.listForUser(eq(ownerId), any()))
                .thenReturn(new Page(List.of(), 0, 1, 20));
        mvc.perform(get("/api/v1/whatsapp/public-templates"))
                .andExpect(status().isOk());
        verify(publicTemplateService).listForUser(eq(ownerId), any());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void listDefaultsLanguageToZhCn() throws Exception {
        Query query = new Query(null, "zh_CN", null, List.of(), List.of(), 1, 20);
        when(publicTemplateService.listForUser(ADMIN_ID, query)).thenReturn(new Page(List.of(), 0, 1, 20));

        mvc.perform(get("/api/v1/whatsapp/public-templates"))
                .andExpect(status().isOk());

        verify(publicTemplateService).listForUser(ADMIN_ID, query);
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void formerCopyEndpointIsNotMapped() throws Exception {
        mvc.perform(post("/api/v1/whatsapp/public-templates/{code}/copy", "scene-001")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void serviceErrorsRetainStructuredCodeAndTraceId() throws Exception {
        when(publicTemplateService.listForUser(eq(ADMIN_ID), any())).thenThrow(new WhatsAppTemplateException(
                "PUBLIC_TEMPLATE_QUERY_INVALID", org.springframework.http.HttpStatus.BAD_REQUEST,
                "Public template query is invalid", Map.of(), null, false));

        mvc.perform(get("/api/v1/whatsapp/public-templates")
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PUBLIC_TEMPLATE_QUERY_INVALID"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
