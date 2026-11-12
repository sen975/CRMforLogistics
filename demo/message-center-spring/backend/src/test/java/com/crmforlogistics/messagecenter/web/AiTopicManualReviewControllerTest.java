package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicService;
import com.crmforlogistics.messagecenter.dto.request.AiTopicManualReviewRequest;
import com.crmforlogistics.messagecenter.dto.response.AiTopicManualReviewResponse;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.TopicProjection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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

class AiTopicManualReviewControllerTest {
    private AiTopicService service;
    private MockMvc mvc;
    private UUID userId;
    private UUID contactId;

    @BeforeEach
    void setUp() {
        service = mock(AiTopicService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AiTopicController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        userId = UUID.randomUUID();
        contactId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), "", List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listsAtMostTwoHundredSourcesAndReportsUnsupportedWeComSummary() throws Exception {
        when(service.listManualReviewSources(eq(userId), eq(contactId), any(), any(), any()))
                .thenReturn(new AiTopicManualReviewResponse.SourceListResponse(
                        contactId, List.of(new AiTopicManualReviewResponse.SourceOption(
                        UUID.randomUUID(), AiTopicModels.SourceType.MESSAGE, "email",
                        Instant.parse("2026-09-01T00:00:00Z"), "主题", "正文", true)),
                        false, "WECOM_SUMMARY_NOT_COMPLETED"));

        mvc.perform(get("/api/v1/contacts/{contactId}/topic-review/sources", contactId)
                        .queryParam("from", "2026-09-01T00:00:00Z")
                        .queryParam("contactIdentityId", UUID.randomUUID().toString())
                        .queryParam("to", "2026-09-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactId").value(contactId.toString()))
                .andExpect(jsonPath("$.items[0].channelType").value("email"))
                .andExpect(jsonPath("$.wecomExcludedReason").value("WECOM_SUMMARY_NOT_COMPLETED"));

        verify(service).listManualReviewSources(eq(userId), eq(contactId), any(),
                eq(Instant.parse("2026-09-01T00:00:00Z")), eq(Instant.parse("2026-09-02T00:00:00Z")));
    }

    @Test
    void previewsSelectedSourcesWithFingerprintAndReturnsPreviewId() throws Exception {
        UUID previewId = UUID.randomUUID();
        when(service.previewManualReview(eq(userId), eq(contactId), any()))
                .thenReturn(new AiTopicManualReviewResponse.PreviewResponse(
                        previewId, contactId, "fingerprint", List.of(), java.util.Map.of(),
                        Instant.parse("2026-09-02T00:00:00Z")));

        mvc.perform(post("/api/v1/contacts/{contactId}/topic-review/preview", contactId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceIds\":[\"" + UUID.randomUUID() + "\"],\"sourceFingerprint\":\"fingerprint\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewId").value(previewId.toString()))
                .andExpect(jsonPath("$.sourceFingerprint").value("fingerprint"));
    }

    @Test
    void appliesPreviewWithIdempotencyKey() throws Exception {
        UUID previewId = UUID.randomUUID();
        when(service.applyManualReview(eq(userId), eq(contactId), eq(previewId), any(), any()))
                .thenReturn(new AiTopicManualReviewResponse.ApplyResponse(
                        previewId, List.of(), true));

        mvc.perform(post("/api/v1/contacts/{contactId}/topic-review/{previewId}/apply", contactId, previewId)
                        .header("Idempotency-Key", "manual-review-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceFingerprint\":\"fingerprint\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));

        verify(service).applyManualReview(eq(userId), eq(contactId), eq(previewId), any(), eq("manual-review-1"));
    }

    @Test
    void previewsAndAppliesFusionBeforeReplacingTopics() throws Exception {
        UUID previewId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(service.previewTopicFusion(eq(userId), eq(contactId), any())).thenReturn(
                new AiTopicManualReviewResponse.FusionPreviewResponse(previewId, List.of(firstId, secondId),
                        "订单履约", "融合后的概要", 8, java.util.Map.of(firstId, 1L, secondId, 2L),
                        Instant.parse("2026-09-02T01:00:00Z")));
        when(service.applyTopicFusion(userId, contactId, previewId, "fusion-1")).thenReturn(
                new TopicProjection(UUID.randomUUID(), "订单履约", "融合后的概要", "AI",
                        Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"),
                        List.of("email"), 8, List.of(), 1, contactId, null, null, null, null,
                        AiTopicModels.OwnerType.CONTACT, contactId, null, false, null, null));

        mvc.perform(post("/api/v1/contacts/{contactId}/topic-fusion/preview", contactId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topicIds\":[\"" + firstId + "\",\"" + secondId
                                + "\"],\"expectedVersions\":{\"" + firstId + "\":1,\"" + secondId + "\":2}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewId").value(previewId.toString()))
                .andExpect(jsonPath("$.title").value("订单履约"));

        mvc.perform(post("/api/v1/contacts/{contactId}/topic-fusion/{previewId}/apply", contactId, previewId)
                        .header("Idempotency-Key", "fusion-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("订单履约"));
    }
}
