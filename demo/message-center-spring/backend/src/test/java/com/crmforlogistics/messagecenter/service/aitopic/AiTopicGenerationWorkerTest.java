package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import com.crmforlogistics.messagecenter.entity.AiTopicGenerationJobEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicGenerationJobMapper;
import com.crmforlogistics.messagecenter.service.wecom.WeComGroupNameRefreshService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTopicGenerationWorkerTest {
    @Test
    void passesJobActorToGenerationService() {
        AiTopicGenerationJobMapper jobs = mock(AiTopicGenerationJobMapper.class);
        AiTopicService service = mock(AiTopicService.class);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        AiTopicConfigHolder config = new AiTopicConfigHolder(
                new AiTopicConfig("https://provider.example", "key", "model", 30, 200, 262144, .65, 1, 3, 120, 30));
        AiTopicGenerationJobEntity job = new AiTopicGenerationJobEntity();
        UUID actor = UUID.randomUUID();
        job.setId(UUID.randomUUID());
        job.setCreatedByUserId(actor);
        job.setAttemptCount(0);
        when(jobs.listRunnable(any(Instant.class), eq(1))).thenReturn(List.of(job));
        when(jobs.claim(eq(job.getId()), any(String.class), any(Instant.class))).thenReturn(1);

        new AiTopicGenerationWorker(jobs, service, gateway, config).runOnce();

        verify(service).generate(job, actor, gateway);
    }

    @Test
    void successfulWeComGroupTopicQueuesANameRefreshWithoutChangingTopicCompletion() {
        AiTopicGenerationJobMapper jobs = mock(AiTopicGenerationJobMapper.class);
        AiTopicService service = mock(AiTopicService.class);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        WeComGroupNameRefreshService refresh = mock(WeComGroupNameRefreshService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<WeComGroupNameRefreshService> refreshProvider = mock(ObjectProvider.class);
        when(refreshProvider.getIfAvailable()).thenReturn(refresh);
        AiTopicConfigHolder config = new AiTopicConfigHolder(
                new AiTopicConfig("https://provider.example", "key", "model", 30, 200, 262144, .65, 1, 3, 120, 30));
        AiTopicGenerationJobEntity job = new AiTopicGenerationJobEntity();
        UUID groupId = UUID.randomUUID();
        job.setId(UUID.randomUUID());
        job.setOwnerType("WECOM_GROUP");
        job.setOwnerId(groupId);
        job.setAttemptCount(0);
        when(jobs.listRunnable(any(Instant.class), eq(1))).thenReturn(List.of(job));
        when(jobs.claim(eq(job.getId()), any(String.class), any(Instant.class))).thenReturn(1);

        new AiTopicGenerationWorker(jobs, service, gateway, config, refreshProvider).runOnce();

        verify(jobs).finish(eq(job.getId()), any(String.class), eq("COMPLETED"), isNull(), isNull(), any(), any());
        verify(refresh).requestAfterTopicUpdated(groupId);
    }

    @Test
    void storesSafeProviderDiagnosticSeparatelyFromPublicErrorCode() {
        AiTopicGenerationJobMapper jobs = mock(AiTopicGenerationJobMapper.class);
        AiTopicService service = mock(AiTopicService.class);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        AiTopicConfigHolder config = new AiTopicConfigHolder(
                new AiTopicConfig("https://provider.example", "key", "model", 30, 200, 262144, .65, 1, 1, 120, 30));
        AiTopicGenerationJobEntity job = new AiTopicGenerationJobEntity();
        UUID actor = UUID.randomUUID();
        job.setId(UUID.randomUUID());
        job.setCreatedByUserId(actor);
        job.setAttemptCount(0);
        when(jobs.listRunnable(any(Instant.class), eq(1))).thenReturn(List.of(job));
        when(jobs.claim(eq(job.getId()), any(String.class), any(Instant.class))).thenReturn(1);
        doThrow(new AiTopicException("AI_PROVIDER_UNAVAILABLE", true, "DNS_ERROR", new RuntimeException("secret")))
                .when(service).generate(job, actor, gateway);

        new AiTopicGenerationWorker(jobs, service, gateway, config).runOnce();

        verify(jobs).finish(eq(job.getId()), any(String.class), eq("FAILED"),
                eq("AI_PROVIDER_UNAVAILABLE"), eq("DNS_ERROR"), any(Instant.class), any(Instant.class));
    }

    @Test
    void truncatesUnboundedExceptionMessageBeforePersistingIt() {
        AiTopicGenerationJobMapper jobs = mock(AiTopicGenerationJobMapper.class);
        AiTopicService service = mock(AiTopicService.class);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        AiTopicConfigHolder config = new AiTopicConfigHolder(
                new AiTopicConfig("https://provider.example", "key", "model", 30, 200, 262144, .65, 1, 3, 120, 30));
        AiTopicGenerationJobEntity job = new AiTopicGenerationJobEntity();
        UUID actor = UUID.randomUUID();
        job.setId(UUID.randomUUID());
        job.setCreatedByUserId(actor);
        job.setAttemptCount(0);
        when(jobs.listRunnable(any(Instant.class), eq(1))).thenReturn(List.of(job));
        when(jobs.claim(eq(job.getId()), any(String.class), any(Instant.class))).thenReturn(1);
        String longMessage = "value too long for type character varying(1000) ".repeat(80);
        doThrow(new IllegalStateException(longMessage)).when(service).generate(job, actor, gateway);

        new AiTopicGenerationWorker(jobs, service, gateway, config).runOnce();

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(jobs).finish(eq(job.getId()), any(String.class), eq("FAILED"),
                eq("AI_GENERATION_FAILED"), message.capture(), any(Instant.class), any(Instant.class));
        assertThat(message.getValue().codePointCount(0, message.getValue().length()))
                .isLessThanOrEqualTo(1000);
    }

    @Test
    void truncatesLongAiTopicDiagnosticBeforePersisting() {
        AiTopicGenerationJobMapper jobs = mock(AiTopicGenerationJobMapper.class);
        AiTopicService service = mock(AiTopicService.class);
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        AiTopicConfigHolder config = new AiTopicConfigHolder(
                new AiTopicConfig("https://provider.example", "key", "model", 30, 200, 262144, .65, 1, 3, 120, 30));
        AiTopicGenerationJobEntity job = new AiTopicGenerationJobEntity();
        UUID actor = UUID.randomUUID();
        job.setId(UUID.randomUUID());
        job.setCreatedByUserId(actor);
        job.setAttemptCount(0);
        when(jobs.listRunnable(any(Instant.class), eq(1))).thenReturn(List.of(job));
        when(jobs.claim(eq(job.getId()), any(String.class), any(Instant.class))).thenReturn(1);
        doThrow(new AiTopicException("AI_RESPONSE_INVALID", false, "x".repeat(5000), new RuntimeException()))
                .when(service).generate(job, actor, gateway);

        new AiTopicGenerationWorker(jobs, service, gateway, config).runOnce();

        ArgumentCaptor<String> diagnostic = ArgumentCaptor.forClass(String.class);
        verify(jobs).finish(eq(job.getId()), any(String.class), eq("FAILED"),
                eq("AI_RESPONSE_INVALID"), diagnostic.capture(), any(Instant.class), any(Instant.class));
        assertThat(diagnostic.getValue().codePointCount(0, diagnostic.getValue().length()))
                .isLessThanOrEqualTo(1000);
    }

    @Test
    void truncateKeepsShortValuesAndNullUnchanged() {
        assertThat(AiTopicGenerationWorker.truncate(null, 5)).isNull();
        assertThat(AiTopicGenerationWorker.truncate("abc", 5)).isEqualTo("abc");
    }

    @Test
    void truncateCutsLongValuesAtCodePointBoundaryWithEllipsis() {
        assertThat(AiTopicGenerationWorker.truncate("abcdefgh", 5)).isEqualTo("ab...");
        assertThat(AiTopicGenerationWorker.truncate("abcdefgh", 5).codePointCount(0, 5)).isEqualTo(5);
    }

    @Test
    void truncateHandlesSurrogatePairsWithoutSplitting() {
        String value = "😀😀😀😀😀";
        String result = AiTopicGenerationWorker.truncate(value, 4);
        assertThat(result.codePointCount(0, result.length())).isLessThanOrEqualTo(4);
        assertThat(result.codePointAt(0)).isEqualTo(0x1F600);
    }
}
