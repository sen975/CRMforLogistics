package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.entity.WeComExternalGroupSyncEntity;
import com.crmforlogistics.messagecenter.mapper.WeComExternalGroupSyncMapper;
import com.crmforlogistics.messagecenter.mapper.WeComInstallationMapper;
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

class WeComExternalGroupSyncWorkerTest {
    private static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");

    @Test
    void springConstructsWorkerThroughItsExplicitAutowiredConstructor() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor());
        factory.registerSingleton("appConfig", mock(AppConfig.class));
        factory.registerSingleton("installationService", mock(WeComInstallationService.class));
        factory.registerSingleton("installationMapper", mock(WeComInstallationMapper.class));
        factory.registerSingleton("syncMapper", mock(WeComExternalGroupSyncMapper.class));
        factory.registerSingleton("externalContacts", mock(WeComExternalContactService.class));
        factory.registerSingleton("pageProcessor", mock(WeComExternalGroupSyncPageProcessor.class));
        factory.registerSingleton("eventHub", mock(EventHub.class));
        factory.registerBeanDefinition("worker", new RootBeanDefinition(WeComExternalGroupSyncWorker.class));

        factory.getBean(WeComExternalGroupSyncWorker.class);
    }

    @Test
    void claimedRunFetchesExactlyOnePageAndDelegatesAtomicPersistence() {
        WeComExternalGroupSyncMapper mapper = mock(WeComExternalGroupSyncMapper.class);
        WeComExternalContactService contacts = mock(WeComExternalContactService.class);
        WeComExternalGroupSyncPageProcessor processor = mock(WeComExternalGroupSyncPageProcessor.class);
        WeComExternalGroupSyncEntity run = run("cursor-1", 7, 0);
        var installation = new ResolvedInstallation("installation", "suite", "corp", "agent", "code", 1);
        var page = new WeComExternalContactService.ExternalGroupPage(List.of("chat-a"), "cursor-2");
        when(mapper.listRunnable(NOW, 1)).thenReturn(List.of(run));
        when(mapper.claim(eq(run.getId()), anyString(), any())).thenReturn(1);
        when(contacts.externalGroupPageForSync(installation, "cursor-1")).thenReturn(page);

        WeComExternalGroupSyncWorker worker = new WeComExternalGroupSyncWorker(
                mapper, contacts, processor, ignored -> installation);

        worker.runOnce(NOW, 1);

        verify(contacts).externalGroupPageForSync(installation, "cursor-1");
        verify(processor).persistClaimedPage(eq(run), eq(page), eq(NOW), anyString());
        verifyNoMoreInteractions(processor);
    }

    @Test
    void pageLimitStopsRunWithoutCallingProviderOrChangingProjection() {
        WeComExternalGroupSyncMapper mapper = mock(WeComExternalGroupSyncMapper.class);
        WeComExternalContactService contacts = mock(WeComExternalContactService.class);
        WeComExternalGroupSyncPageProcessor processor = mock(WeComExternalGroupSyncPageProcessor.class);
        WeComExternalGroupSyncEntity run = run("cursor-1001", 1000, 0);
        when(mapper.listRunnable(NOW, 1)).thenReturn(List.of(run));
        when(mapper.claim(eq(run.getId()), anyString(), any())).thenReturn(1);

        new WeComExternalGroupSyncWorker(mapper, contacts, processor, ignored -> mock(ResolvedInstallation.class))
                .runOnce(NOW, 1);

        verify(mapper).fail(eq(run.getId()), anyString(), eq("WECOM_EXTERNAL_GROUP_PAGE_LIMIT"), eq(NOW));
        verifyNoInteractions(contacts, processor);
    }

    @Test
    void finalPagePublishesOneProjectionCompletedEventAfterPersistenceReturns() {
        WeComExternalGroupSyncMapper mapper = mock(WeComExternalGroupSyncMapper.class);
        WeComExternalContactService contacts = mock(WeComExternalContactService.class);
        WeComExternalGroupSyncPageProcessor processor = mock(WeComExternalGroupSyncPageProcessor.class);
        EventHub events = mock(EventHub.class);
        WeComExternalGroupSyncEntity run = run("", 0, 0);
        var installation = new ResolvedInstallation("installation", "suite", "corp", "agent", "code", 1);
        var page = new WeComExternalContactService.ExternalGroupPage(List.of(), "");
        when(mapper.listRunnable(NOW, 1)).thenReturn(List.of(run));
        when(mapper.claim(eq(run.getId()), anyString(), any())).thenReturn(1);
        when(contacts.externalGroupPageForSync(installation, "")).thenReturn(page);

        new WeComExternalGroupSyncWorker(mapper, contacts, processor, ignored -> installation, events)
                .runOnce(NOW, 1);

        var order = inOrder(processor, events);
        order.verify(processor).persistClaimedPage(eq(run), eq(page), eq(NOW), anyString());
        order.verify(events).publish(eq("wecom-group-kind-sync-completed"),
                contains(run.getInstallationId().toString()));
    }

    @Test
    void leaseLostDuringFailureDoesNotDeleteAnotherWorkersStagingItems() {
        WeComExternalGroupSyncMapper mapper = mock(WeComExternalGroupSyncMapper.class);
        WeComExternalContactService contacts = mock(WeComExternalContactService.class);
        WeComExternalGroupSyncPageProcessor processor = mock(WeComExternalGroupSyncPageProcessor.class);
        WeComExternalGroupSyncEntity run = run("cursor-1001", 1000, 0);
        when(mapper.listRunnable(NOW, 1)).thenReturn(List.of(run));
        when(mapper.claim(eq(run.getId()), anyString(), any())).thenReturn(1);
        when(mapper.fail(eq(run.getId()), anyString(), anyString(), eq(NOW))).thenReturn(0);

        new WeComExternalGroupSyncWorker(mapper, contacts, processor, ignored -> mock(ResolvedInstallation.class))
                .runOnce(NOW, 1);

        verify(mapper, never()).deleteItems(run.getId());
    }

    private static WeComExternalGroupSyncEntity run(String cursor, int pageCount, int attemptCount) {
        WeComExternalGroupSyncEntity run = new WeComExternalGroupSyncEntity();
        run.setId(UUID.randomUUID());
        run.setInstallationId(UUID.randomUUID());
        run.setCursor(cursor);
        run.setPageCount(pageCount);
        run.setAttemptCount(attemptCount);
        return run;
    }
}
