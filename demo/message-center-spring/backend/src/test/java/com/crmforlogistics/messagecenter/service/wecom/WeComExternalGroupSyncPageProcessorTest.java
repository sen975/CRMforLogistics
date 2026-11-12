package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.entity.WeComExternalGroupSyncEntity;
import com.crmforlogistics.messagecenter.mapper.WeComExternalGroupSyncMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WeComExternalGroupSyncPageProcessorTest {
    private static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");

    @Test
    void intermediatePagePersistsIdsAndCursorWithoutChangingGroupKinds() {
        WeComExternalGroupSyncMapper mapper = mock(WeComExternalGroupSyncMapper.class);
        WeComExternalGroupSyncEntity run = run();
        var page = new WeComExternalContactService.ExternalGroupPage(
                List.of("chat-a", "chat-b"), "cursor-2");
        when(mapper.advance(run.getId(), "worker-1", "cursor-2", NOW)).thenReturn(1);

        new WeComExternalGroupSyncPageProcessor(mapper).persistClaimedPage(run, page, NOW, "worker-1");

        verify(mapper).insertItems(run.getId(), List.of("chat-a", "chat-b"));
        verify(mapper).advance(run.getId(), "worker-1", "cursor-2", NOW);
        verify(mapper, never()).markSeenExternal(any(), any());
        verify(mapper, never()).markUnknownUnseenInternal(any(), any());
    }

    @Test
    void finalPageCommitsExternalAndPreviouslyUnknownInternalProjection() {
        WeComExternalGroupSyncMapper mapper = mock(WeComExternalGroupSyncMapper.class);
        WeComExternalGroupSyncEntity run = run();
        var page = new WeComExternalContactService.ExternalGroupPage(List.of("chat-last"), "");
        when(mapper.complete(run.getId(), "worker-1", NOW)).thenReturn(1);

        new WeComExternalGroupSyncPageProcessor(mapper).persistClaimedPage(run, page, NOW, "worker-1");

        verify(mapper).insertItems(run.getId(), List.of("chat-last"));
        verify(mapper).markSeenExternal(run.getInstallationId(), run.getId());
        verify(mapper).markUnknownUnseenInternal(run.getInstallationId(), run.getId());
        verify(mapper).complete(run.getId(), "worker-1", NOW);
        verify(mapper).deleteItems(run.getId());
        verify(mapper, never()).advance(any(), any(), any(), any());
    }

    @Test
    void finalProjectionMethodIsTransactional() throws Exception {
        assertThat(WeComExternalGroupSyncPageProcessor.class
                .getMethod("persistClaimedPage", WeComExternalGroupSyncEntity.class,
                        WeComExternalContactService.ExternalGroupPage.class, Instant.class, String.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    private static WeComExternalGroupSyncEntity run() {
        WeComExternalGroupSyncEntity run = new WeComExternalGroupSyncEntity();
        run.setId(UUID.randomUUID());
        run.setInstallationId(UUID.randomUUID());
        run.setPageCount(0);
        run.setAttemptCount(1);
        return run;
    }
}
