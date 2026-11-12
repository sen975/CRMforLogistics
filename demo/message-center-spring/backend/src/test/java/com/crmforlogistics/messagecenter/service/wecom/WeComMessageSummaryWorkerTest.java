package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataMessageEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeComMessageSummaryWorkerTest {
    private final Instant now = Instant.parse("2026-08-31T09:00:00Z");
    private final UUID installationId = UUID.randomUUID();

    @Test
    void springSelectsTheProductionConstructor() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor());
        AppConfig config = mock(AppConfig.class);
        when(config.wecomMessageSummaryBatchSize()).thenReturn(20);
        when(config.wecomMessageSummaryMaxConcurrency()).thenReturn(2);
        when(config.wecomMessageSummaryMaxTransientAttempts()).thenReturn(20);
        when(config.wecomMessageSummaryMaxBackoffSeconds()).thenReturn(900);
        when(config.wecomMessageSummaryPollIntervalSeconds()).thenReturn(1);
        factory.registerSingleton("appConfig", config);
        factory.registerSingleton("weComInstallationService", mock(WeComInstallationService.class));
        factory.registerSingleton("weComMessageSummaryRepository", mock(WeComMessageSummaryRepository.class));
        factory.registerSingleton("weComChatDataMessageMapper", mock(WeComChatDataMessageMapper.class));
        factory.registerSingleton("weComCredentialProtector", mock(WeComCredentialProtector.class));
        factory.registerSingleton("weComSummaryGateway", mock(WeComSummaryGateway.class));
        factory.registerBeanDefinition("weComMessageSummaryWorker",
                new RootBeanDefinition(WeComMessageSummaryWorker.class));

        assertNotNull(factory.getBean(WeComMessageSummaryWorker.class));
    }

    @Test
    void submitsExactlyOneMessageAndStoresCompletedSummary() {
        WeComMessageSummaryRepository repository = mock(WeComMessageSummaryRepository.class);
        WeComChatDataMessageMapper messages = mock(WeComChatDataMessageMapper.class);
        WeComCredentialProtector credentials = mock(WeComCredentialProtector.class);
        WeComSummaryGateway gateway = mock(WeComSummaryGateway.class);
        var job = new WeComMessageSummaryRepository.LeasedJob(UUID.randomUUID(), installationId, "corp", null,
                "m-1", 100L, "PENDING", null, 0, now);
        when(repository.leaseNext(anyString(), eq(now), any())).thenReturn(Optional.of(job));
        WeComChatDataMessageEntity entity = new WeComChatDataMessageEntity();
        entity.setSecretKey("encrypted");
        when(messages.findSummaryReference(installationId, "m-1")).thenReturn(entity);
        when(credentials.revealSecretKey("encrypted")).thenReturn("secret");
        when(gateway.submit(any(), argThat(list -> list.size() == 1 && list.get(0).msgid().equals("m-1")), any()))
                .thenReturn(new WeComSummaryGateway.SubmitResult(0, 0, "job-1", "{\"errcode\":0}"));
        when(gateway.poll(any(), eq("job-1"), any()))
                .thenReturn(new WeComSummaryGateway.PollResult(0, 1, "job-1", "摘要", "{\"status\":1}"));

        var worker = new WeComMessageSummaryWorker(repository, messages, credentials, gateway,
                corp -> new ResolvedInstallation(installationId.toString(), "suite", corp, "agent", "code", 1),
                "worker", 20, 1, 20, 900, 1);
        worker.runOnce(now);
        verify(gateway).submit(any(), argThat(list -> list.size() == 1), any());
        verify(repository).markSubmitted(eq(job.id()), eq("job-1"), any());
    }

    @Test
    void invalidOfficialPayloadIsRecordedForRetry() {
        WeComMessageSummaryRepository repository = mock(WeComMessageSummaryRepository.class);
        WeComChatDataMessageMapper messages = mock(WeComChatDataMessageMapper.class);
        WeComCredentialProtector credentials = mock(WeComCredentialProtector.class);
        WeComSummaryGateway gateway = mock(WeComSummaryGateway.class);
        var job = new WeComMessageSummaryRepository.LeasedJob(UUID.randomUUID(), installationId, "corp", null,
                "m-1", 100L, "SUBMITTED", "job-1", 0, now);
        when(repository.leaseNext(anyString(), eq(now), any())).thenReturn(Optional.of(job));
        WeComChatDataMessageEntity entity = new WeComChatDataMessageEntity();
        entity.setSecretKey("encrypted");
        when(messages.findSummaryReference(installationId, "m-1")).thenReturn(entity);
        when(credentials.revealSecretKey("encrypted")).thenReturn("secret");
        when(gateway.poll(any(), eq("job-1"), any()))
                .thenReturn(new WeComSummaryGateway.PollResult(0, 1, "job-1", "", "{\"status\":1}"));

        var worker = new WeComMessageSummaryWorker(repository, messages, credentials, gateway,
                corp -> new ResolvedInstallation(installationId.toString(), "suite", corp, "agent", "code", 1),
                "worker", 20, 1, 20, 900, 1);
        worker.runOnce(now);
        verify(repository).markRetry(eq(job.id()), eq("AI_RESPONSE_INVALID"), anyString(),
                eq("official result is missing a non-empty summary"), eq("RESPONSE_DATA"), any());
    }

    @Test
    void completedSummaryRemainsSavedWhenTopicActivityProjectionFails() {
        WeComMessageSummaryRepository repository = mock(WeComMessageSummaryRepository.class);
        WeComChatDataMessageMapper messages = mock(WeComChatDataMessageMapper.class);
        WeComCredentialProtector credentials = mock(WeComCredentialProtector.class);
        WeComSummaryGateway gateway = mock(WeComSummaryGateway.class);
        com.crmforlogistics.messagecenter.service.aitopic.WeComSummaryTopicActivityBridge bridge =
                mock(com.crmforlogistics.messagecenter.service.aitopic.WeComSummaryTopicActivityBridge.class);
        var job = new WeComMessageSummaryRepository.LeasedJob(UUID.randomUUID(), installationId, "corp", UUID.randomUUID(),
                "m-1", 100L, "RETRY_WAIT", "job-1", 10, now);
        when(repository.leaseNext(anyString(), eq(now), any())).thenReturn(Optional.of(job));
        WeComChatDataMessageEntity entity = new WeComChatDataMessageEntity();
        entity.setSecretKey("encrypted");
        entity.setMsgtype("1");
        when(messages.findSummaryReference(installationId, "m-1")).thenReturn(entity);
        when(credentials.revealSecretKey("encrypted")).thenReturn("secret");
        when(gateway.poll(any(), eq("job-1"), any()))
                .thenReturn(new WeComSummaryGateway.PollResult(0, 1, "job-1", "摘要", "{\"status\":1}"));
        doThrow(new IllegalStateException("topic projection failed"))
                .when(bridge).recordSummaryActivity(job);

        var worker = new WeComMessageSummaryWorker(repository, messages, credentials, gateway,
                corp -> new ResolvedInstallation(installationId.toString(), "suite", corp, "agent", "code", 1),
                "worker", 20, 1, 20, 900, 1, bridge);

        worker.runOnce(now);

        verify(repository).markCompleted(eq(job.id()), eq("摘要"), eq("{\"status\":1}"),
                eq("OFFICIAL_RESULT"), eq(now));
        verify(repository, never()).markRetry(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void mediaMessageIsTerminallySkippedWithoutCallingSummaryGateway() {
        WeComMessageSummaryRepository repository = mock(WeComMessageSummaryRepository.class);
        WeComChatDataMessageMapper messages = mock(WeComChatDataMessageMapper.class);
        WeComCredentialProtector credentials = mock(WeComCredentialProtector.class);
        WeComSummaryGateway gateway = mock(WeComSummaryGateway.class);
        var job = new WeComMessageSummaryRepository.LeasedJob(UUID.randomUUID(), installationId, "corp", null,
                "m-image", 100L, "PENDING", null, 0, now);
        when(repository.leaseNext(anyString(), eq(now), any())).thenReturn(Optional.of(job));
        WeComChatDataMessageEntity entity = new WeComChatDataMessageEntity();
        entity.setMsgtype("2");
        when(messages.findSummaryReference(installationId, "m-image")).thenReturn(entity);

        var worker = new WeComMessageSummaryWorker(repository, messages, credentials, gateway,
                corp -> new ResolvedInstallation(installationId.toString(), "suite", corp, "agent", "code", 1),
                "worker", 20, 1, 20, 900, 1);
        worker.runOnce(now);

        verify(repository).markFailed(eq(job.id()), eq("UNSUPPORTED_MEDIA"), eq("UNSUPPORTED_MEDIA"),
                eq(""), eq("official summary supports text messages only"), eq("UNSUPPORTED_MEDIA"), eq(now));
        verifyNoInteractions(credentials, gateway);
    }

    @Test
    void mediaTransitionFailureDoesNotBlockNextPendingTextMessage() {
        WeComMessageSummaryRepository repository = mock(WeComMessageSummaryRepository.class);
        WeComChatDataMessageMapper messages = mock(WeComChatDataMessageMapper.class);
        WeComCredentialProtector credentials = mock(WeComCredentialProtector.class);
        WeComSummaryGateway gateway = mock(WeComSummaryGateway.class);
        var mediaJob = new WeComMessageSummaryRepository.LeasedJob(UUID.randomUUID(), installationId, "corp", null,
                "m-image", 100L, "PENDING", null, 0, now);
        var textJob = new WeComMessageSummaryRepository.LeasedJob(UUID.randomUUID(), installationId, "corp", null,
                "m-text", 101L, "PENDING", null, 0, now);
        when(repository.leaseNext(anyString(), eq(now), any()))
                .thenReturn(Optional.of(mediaJob), Optional.of(textJob));
        WeComChatDataMessageEntity media = new WeComChatDataMessageEntity();
        media.setMsgtype("2");
        WeComChatDataMessageEntity text = new WeComChatDataMessageEntity();
        text.setMsgtype("1");
        text.setSecretKey("encrypted");
        when(messages.findSummaryReference(installationId, "m-image")).thenReturn(media);
        when(messages.findSummaryReference(installationId, "m-text")).thenReturn(text);
        doThrow(new IllegalStateException("summary failure transition rejected"))
                .when(repository).markFailed(mediaJob.id(), "UNSUPPORTED_MEDIA", "UNSUPPORTED_MEDIA", "",
                        "official summary supports text messages only", "UNSUPPORTED_MEDIA", now);
        when(credentials.revealSecretKey("encrypted")).thenReturn("secret");
        when(gateway.submit(any(), anyList(), any()))
                .thenReturn(new WeComSummaryGateway.SubmitResult(0, 0, "job-text", "{}"));

        var worker = new WeComMessageSummaryWorker(repository, messages, credentials, gateway,
                corp -> new ResolvedInstallation(installationId.toString(), "suite", corp, "agent", "code", 1),
                "worker", 20, 2, 20, 900, 1);

        assertEquals(2, worker.runOnce(now));
        verify(gateway).submit(any(), argThat(list -> list.size() == 1
                && list.get(0).msgid().equals("m-text")), any());
        verify(repository).markSubmitted(eq(textJob.id()), eq("job-text"), any());
    }
}
