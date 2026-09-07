package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.entity.WeComGroupNameRefreshJobEntity;
import com.crmforlogistics.messagecenter.service.wecom.WeComGroupNameRefreshService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WeComGroupNameRefreshControllerTest {
    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void submitsRefreshForCurrentUserAndReturnsAcceptedProjection() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        WeComGroupNameRefreshService service = mock(WeComGroupNameRefreshService.class);
        WeComGroupNameRefreshJobEntity job = new WeComGroupNameRefreshJobEntity();
        job.setId(UUID.randomUUID());
        job.setSourceConversationId(sourceId);
        job.setTriggerSource("MANUAL");
        job.setStatus("PENDING");
        job.setCreatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        when(service.requestManual(userId, sourceId)).thenReturn(job);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(userId.toString(), "n/a", java.util.List.of()));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WeComGroupNameRefreshController(service)).build();

        mvc.perform(post("/api/v1/wecom/groups/{sourceConversationId}/name-refresh", sourceId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(job.getId().toString()))
                .andExpect(jsonPath("$.sourceConversationId").value(sourceId.toString()))
                .andExpect(jsonPath("$.triggerSource").value("MANUAL"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.leaseOwner").doesNotExist())
                .andExpect(jsonPath("$.errorDiagnostic").doesNotExist());
    }
}
