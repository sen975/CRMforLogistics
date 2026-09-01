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
                eq("RESPONSE_DATA"), any());
    }
}
