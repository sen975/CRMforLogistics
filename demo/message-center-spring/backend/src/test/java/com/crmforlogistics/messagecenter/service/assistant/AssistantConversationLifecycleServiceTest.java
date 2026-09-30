package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.entity.AssistantConversationEntity;
import com.crmforlogistics.messagecenter.mapper.AssistantConversationMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AssistantConversationLifecycleServiceTest {
    private static final UUID USER = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");

    private final AssistantConversationMapper mapper = mock(AssistantConversationMapper.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AssistantConversationLifecycleService service =
            new AssistantConversationLifecycleService(mapper, clock);

    @Test
    void marksConversationExpiredAtThirtyDaysAndRejectsWrites() {
        AssistantConversationEntity stale = row("ACTIVE", NOW.minusSeconds(30L * 24 * 60 * 60 + 1));
        when(mapper.findOwned(USER, CONVERSATION)).thenReturn(stale);
        when(mapper.markExpired(eq(USER), eq(CONVERSATION), any(Instant.class), any(Instant.class))).thenReturn(1);

        assertThatThrownBy(() -> service.requireActive(USER, CONVERSATION))
                .isInstanceOf(AssistantException.class)
                .extracting("code").isEqualTo(AssistantException.CONVERSATION_EXPIRED);
        verify(mapper).markExpired(eq(USER), eq(CONVERSATION), eq(NOW), eq(NOW.minus(AssistantConversationLifecycleService.INACTIVITY_TTL)));
    }

    @Test
    void staleArchivedConversationExpiresAndCannotBeReadBack() {
        AssistantConversationEntity stale = row("ARCHIVED", NOW.minus(AssistantConversationLifecycleService.INACTIVITY_TTL)
                .minusSeconds(1));
        when(mapper.findOwned(USER, CONVERSATION)).thenReturn(stale);
        when(mapper.markExpired(eq(USER), eq(CONVERSATION), any(Instant.class), any(Instant.class))).thenReturn(1);

        assertThatThrownBy(() -> service.requireReadable(USER, CONVERSATION))
                .isInstanceOf(AssistantException.class)
                .extracting("code").isEqualTo(AssistantException.CONVERSATION_EXPIRED);
        verify(mapper).markExpired(eq(USER), eq(CONVERSATION), eq(NOW), eq(NOW.minus(AssistantConversationLifecycleService.INACTIVITY_TTL)));
    }

    @Test
    void scheduledExpirationUsesTheSameThirtyDayInactivityBoundary() {
        when(mapper.expireStale(NOW, NOW.minus(AssistantConversationLifecycleService.INACTIVITY_TTL))).thenReturn(4);

        assertThat(service.expireStale()).isEqualTo(4);

        verify(mapper).expireStale(NOW, NOW.minusSeconds(30L * 24 * 60 * 60));
    }

    @Test
    void exactlyThirtyDaysIsStillActive() {
        AssistantConversationEntity active = row("ACTIVE", NOW.minusSeconds(30L * 24 * 60 * 60));
        when(mapper.findOwned(USER, CONVERSATION)).thenReturn(active);

        service.requireActive(USER, CONVERSATION);

        verify(mapper, never()).markExpired(any(), any(), any(), any());
        verify(mapper).touchActive(USER, CONVERSATION, NOW);
    }

    @Test
    void acceptingActivityRefreshesTtlBeforeLongRunningAssistantWork() {
        AssistantConversationEntity active = row("ACTIVE", NOW.minusSeconds(60));
        when(mapper.findOwned(USER, CONVERSATION)).thenReturn(active);

        service.requireActive(USER, CONVERSATION);

        verify(mapper).touchActive(USER, CONVERSATION, NOW);
    }

    @Test
    void concurrentActivityThatWinsExpiryRaceKeepsConversationActive() {
        AssistantConversationEntity stale = row("ACTIVE", NOW.minus(AssistantConversationLifecycleService.INACTIVITY_TTL)
                .minusSeconds(1));
        AssistantConversationEntity refreshed = row("ACTIVE", NOW.minusSeconds(1));
        when(mapper.findOwned(USER, CONVERSATION)).thenReturn(stale, refreshed);
        when(mapper.markExpired(eq(USER), eq(CONVERSATION), any(Instant.class), any(Instant.class))).thenReturn(0);

        service.requireActive(USER, CONVERSATION);

        verify(mapper).touchActive(USER, CONVERSATION, NOW);
    }

    @Test
    void creatingConversationArchivesCurrentActiveAndCreatesNewOwnedConversation() {
        UUID nextConversation = UUID.randomUUID();
        when(mapper.insertActive(nextConversation, USER, NOW)).thenReturn(1);

        UUID created = service.createNew(USER, nextConversation);

        assertThat(created).isEqualTo(nextConversation);
        verify(mapper).archiveActive(USER, NOW);
        verify(mapper).insertActive(nextConversation, USER, NOW);
    }

    @Test
    void creatingConversationWithAnExistingIdFailsInsteadOfReturningAnUnregisteredConversation() {
        when(mapper.insertActive(CONVERSATION, USER, NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.createNew(USER, CONVERSATION))
                .isInstanceOf(AssistantException.class)
                .extracting("code").isEqualTo(AssistantException.CONVERSATION_ALREADY_EXISTS);
    }

    @Test
    void openingArchivedConversationArchivesThePreviousActiveConversation() {
        AssistantConversationEntity target = row("ARCHIVED", NOW.minusSeconds(60));
        when(mapper.findOwned(USER, CONVERSATION)).thenReturn(target);
        when(mapper.archiveActive(USER, NOW)).thenReturn(1);
        when(mapper.activateArchived(USER, CONVERSATION, NOW)).thenReturn(1);

        service.open(USER, CONVERSATION);

        verify(mapper).archiveActive(USER, NOW);
        verify(mapper).activateArchived(USER, CONVERSATION, NOW);
    }

    @Test
    void deletedConversationIsSoftDeletedAndNeverListed() {
        AssistantConversationEntity active = row("ACTIVE", NOW);
        when(mapper.findOwned(USER, CONVERSATION)).thenReturn(active);
        when(mapper.markDeleted(USER, CONVERSATION, NOW)).thenReturn(1);
        when(mapper.listVisible(USER)).thenReturn(List.of());

        service.delete(USER, CONVERSATION);

        verify(mapper).markDeleted(USER, CONVERSATION, NOW);
        assertThat(service.listVisible(USER)).isEmpty();
    }

    private static AssistantConversationEntity row(String status, Instant lastActivity) {
        AssistantConversationEntity row = new AssistantConversationEntity();
        row.setId(CONVERSATION);
        row.setUserId(USER);
        row.setStatus(status);
        row.setLastActivityAt(lastActivity);
        return row;
    }
}
