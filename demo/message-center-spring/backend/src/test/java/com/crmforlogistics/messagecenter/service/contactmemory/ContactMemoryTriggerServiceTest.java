package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.entity.ContactMemoryTriggerEventEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryTriggerEventMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContactMemoryTriggerServiceTest {

    @Test
    void inboundInsertCreatesOneReplayableEvent() {
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryTriggerEventMapper events = mock(ContactMemoryTriggerEventMapper.class);
        UUID contactId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        ContactMemoryTriggerService service = new ContactMemoryTriggerService(states, events);

        service.markInboundPersisted(contactId, messageId, 7L,
                Instant.parse("2026-09-11T01:00:00Z"), Instant.parse("2026-09-11T01:00:01Z"));

        verify(events).enqueue(eq(contactId), eq(messageId), eq(7L), any(), any());
        verifyNoInteractions(states);
    }

    @Test
    void duplicateProjectionDoesNotCreateDuplicateTriggerEvents() {
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryTriggerEventMapper events = mock(ContactMemoryTriggerEventMapper.class);
        when(events.enqueue(any(), any(), any(), any(), any())).thenReturn(0);

        UUID contactId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        new ContactMemoryTriggerService(states, events).markInboundPersisted(
                contactId, messageId, null, null, null);

        verify(events).enqueue(eq(contactId), eq(messageId), isNull(), isNull(), isNull());
        verifyNoInteractions(states);
    }

    @Test
    void failedStateMarkingIsRecoveredBySchedulerReplay() {
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryTriggerEventMapper events = mock(ContactMemoryTriggerEventMapper.class);
        ContactMemoryTriggerEventEntity event = event();
        when(events.claimDue(any(), any(), any(), eq(10))).thenReturn(List.of(event), List.of(event));
        when(states.markDirty(event.getContactId(), event.getOwnerUserId(), event.getReceivedAt()))
                .thenThrow(new RuntimeException("temporary state failure"))
                .thenReturn(1);
        when(events.markFailed(any(), any(), any(), any(), any(Integer.class), any(), any(Boolean.class)))
                .thenReturn(1);
        when(events.markApplied(event.getId(), event.getLeaseToken())).thenReturn(1);

        ContactMemoryTriggerService service = new ContactMemoryTriggerService(states, events);
        int claimed = service.replayDue(
                Instant.parse("2026-09-11T02:00:00Z"), 10);
        int replayed = service.replayDue(
                Instant.parse("2026-09-11T02:01:00Z"), 10);

        org.assertj.core.api.Assertions.assertThat(claimed).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(replayed).isEqualTo(1);
        verify(events).markFailed(eq(event.getId()), eq(event.getLeaseToken()),
                eq("STATE_MARK_DIRTY_FAILED"), any(), eq(1), any(), eq(false));
        verify(states, org.mockito.Mockito.times(2)).markDirty(
                event.getContactId(), event.getOwnerUserId(), event.getReceivedAt());
        verify(events).markApplied(event.getId(), event.getLeaseToken());
    }

    private static ContactMemoryTriggerEventEntity event() {
        ContactMemoryTriggerEventEntity event = new ContactMemoryTriggerEventEntity();
        event.setId(UUID.randomUUID());
        event.setContactId(UUID.randomUUID());
        event.setOwnerUserId(UUID.randomUUID());
        event.setLeaseToken(UUID.randomUUID());
        event.setReceivedAt(Instant.parse("2026-09-11T01:00:01Z"));
        event.setAttemptCount(0);
        return event;
    }
}
