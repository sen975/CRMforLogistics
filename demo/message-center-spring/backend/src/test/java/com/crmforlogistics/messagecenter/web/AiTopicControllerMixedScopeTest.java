package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.service.aitopic.AiTopicException;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.OwnerType;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.GenerationProjection;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.GenerationStatus;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.GroupTopicTimelineResponse;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicInboxRequestProjection;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiTopicControllerMixedScopeTest {
    private AiTopicService service;
    private MockMvc mvc;
    private UUID userId;

    @BeforeEach
    void setUp() {
        service = mock(AiTopicService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AiTopicController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), "", List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listsPendingGroupStoreRequestsWithOwnerProjection() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(service.listPendingStoreRequests(userId)).thenReturn(List.of(new TopicInboxRequestProjection(
                requestId, topicId, "报价讨论", OwnerType.WECOM_GROUP, ownerId, "华东报价群",
                userId, Instant.parse("2026-09-01T01:00:00Z"))));

        mvc.perform(get("/api/v1/topic-inbox/requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(requestId.toString()))
                .andExpect(jsonPath("$[0].ownerType").value("WECOM_GROUP"))
                .andExpect(jsonPath("$[0].ownerLabel").value("华东报价群"));

        verify(service).listPendingStoreRequests(userId);
    }

    @Test
    void mapsNonAdminApprovalToForbidden() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(service.approveStore(eq(userId), eq(requestId), any()))
                .thenThrow(new AiTopicException("TOPIC_ADMIN_REQUIRED", false));

        mvc.perform(post("/api/v1/topic-inbox/{requestId}/approve", requestId)
                        .header("Idempotency-Key", "approval-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TOPIC_ADMIN_REQUIRED"));
    }

    @Test
    void mapsStoreStateConflictToConflict() throws Exception {
        UUID topicId = UUID.randomUUID();
        when(service.submitStore(eq(userId), eq(topicId), any()))
                .thenThrow(new AiTopicException("TOPIC_STORE_NOT_READY", false));

        mvc.perform(post("/api/v1/topics/{topicId}/store", topicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "store-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOPIC_STORE_NOT_READY"));
    }

    @Test
    void forwardsRepositoryOwnerTypeFilter() throws Exception {
        when(service.listStored(userId, null, "WECOM_GROUP", 1, 20)).thenReturn(new Page<>(1, 20));

        mvc.perform(get("/api/v1/topic-repository").queryParam("ownerType", "WECOM_GROUP"))
                .andExpect(status().isOk());

        verify(service).listStored(userId, null, "WECOM_GROUP", 1, 20);
    }

    @Test
    void exposesAGroupTopicTimelineEndpoint() throws Exception {
        UUID sourceConversationId = UUID.randomUUID();
        when(service.getGroupTopics(userId, sourceConversationId)).thenReturn(new GroupTopicTimelineResponse(
                sourceConversationId, new GenerationProjection(GenerationStatus.NOT_STARTED, null, null, null),
                List.of(), false));

        mvc.perform(get("/api/v1/wecom/groups/{sourceConversationId}/topics", sourceConversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceConversationId").value(sourceConversationId.toString()))
                .andExpect(jsonPath("$.weComUnsupported").value(false));

        verify(service).getGroupTopics(userId, sourceConversationId);
    }

    @Test
    void retriesFailedGroupTopicGeneration() throws Exception {
        UUID sourceConversationId = UUID.randomUUID();
        when(service.retryGroupGeneration(userId, sourceConversationId)).thenReturn(
                new GenerationProjection(GenerationStatus.GENERATING, UUID.randomUUID(), null, Instant.now()));

        mvc.perform(post("/api/v1/wecom/groups/{sourceConversationId}/topics/retry", sourceConversationId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("GENERATING"));

        verify(service).retryGroupGeneration(userId, sourceConversationId);
    }

    @Test
    void requiresIdempotencyKeyWhenRejectingStoreRequest() throws Exception {
        mvc.perform(post("/api/v1/topic-inbox/{requestId}/reject", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"不再需要\"}"))
                .andExpect(status().isBadRequest());
    }
}
