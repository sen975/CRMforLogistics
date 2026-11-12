package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.WeComGroupNameRefreshJobEntity;
import com.crmforlogistics.messagecenter.entity.WeComSourceConversationEntity;
import com.crmforlogistics.messagecenter.mapper.WeComGroupNameRefreshJobMapper;
import com.crmforlogistics.messagecenter.mapper.WeComInstallationMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceConversationMapper;
import com.crmforlogistics.messagecenter.service.event.EventHub;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeComGroupNameRefreshWorkerTest {
    @Test
    void springCanConstructWorkerWithoutAnInstallationResolverBean() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor());
        factory.registerSingleton("appConfig", mock(AppConfig.class));
        factory.registerSingleton("weComInstallationService", mock(WeComInstallationService.class));
        factory.registerSingleton("weComInstallationMapper", mock(WeComInstallationMapper.class));
        factory.registerSingleton("weComGroupNameRefreshJobMapper", mock(WeComGroupNameRefreshJobMapper.class));
        factory.registerSingleton("weComSourceConversationMapper", mock(WeComSourceConversationMapper.class));
        factory.registerSingleton("weComGroupNameRefreshService", mock(WeComGroupNameRefreshService.class));
        factory.registerSingleton("eventHub", mock(EventHub.class));
        factory.registerBeanDefinition("weComGroupNameRefreshWorker",
                new RootBeanDefinition(WeComGroupNameRefreshWorker.class));

        WeComGroupNameRefreshWorker worker = factory.getBean(WeComGroupNameRefreshWorker.class);

        org.assertj.core.api.Assertions.assertThat(worker).isNotNull();
    }

    @Test
    void successfulLookupUpdatesProjectionAndPublishesFinalEvent() {
        WeComGroupNameRefreshJobMapper jobs = mock(WeComGroupNameRefreshJobMapper.class);
        WeComSourceConversationMapper conversations = mock(WeComSourceConversationMapper.class);
        WeComGroupNameRefreshService service = mock(WeComGroupNameRefreshService.class);
        EventHub events = mock(EventHub.class);
        UUID groupId = UUID.randomUUID();
        WeComGroupNameRefreshJobEntity job = job(groupId);
        when(jobs.listRunnable(any(), anyInt())).thenReturn(List.of(job));
        when(jobs.claim(eq(job.getId()), anyString(), any())).thenReturn(1);
        when(conversations.selectById(groupId)).thenReturn(group(groupId));
        when(service.lookup(any(ResolvedInstallation.class), eq("chat-1"))).thenReturn("报价项目群");

        new WeComGroupNameRefreshWorker(jobs, conversations, service,
                ignored -> new ResolvedInstallation("installation", "suite", "corp", "agent", "code", 1), events)
                .runOnce(Instant.parse("2026-09-01T00:00:00Z"), 5);

        verify(conversations).updateNameResolution(eq(groupId), eq("报价项目群"), eq("RESOLVED"), any(), isNull(), isNull());
        verify(jobs).finish(eq(job.getId()), anyString(), eq("COMPLETED"), isNull(), isNull(), any(), any());
        verify(events).publish(eq("wecom-group-name-refresh-completed"), contains(groupId.toString()));
    }

    private static WeComGroupNameRefreshJobEntity job(UUID groupId) {
        WeComGroupNameRefreshJobEntity value = new WeComGroupNameRefreshJobEntity();
        value.setId(UUID.randomUUID()); value.setSourceConversationId(groupId); value.setAttemptCount(0);
        return value;
    }

    private static WeComSourceConversationEntity group(UUID groupId) {
        WeComSourceConversationEntity value = new WeComSourceConversationEntity();
        value.setId(groupId); value.setInstallationId(UUID.randomUUID()); value.setConversationType("GROUP");
        value.setProviderConversationKey("group:chat-1");
        return value;
    }
}
