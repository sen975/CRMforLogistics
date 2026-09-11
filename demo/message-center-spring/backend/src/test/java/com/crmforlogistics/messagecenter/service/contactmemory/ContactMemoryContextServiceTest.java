package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.entity.ContactMemoryStateEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContactMemoryContextServiceTest {

    @Test
    void contextExcludesMessagesAfterCutoffAndNeverReadsAnotherOwner() {
        ContactMapper contacts = mock(ContactMapper.class);
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryMapper memory = mock(ContactMemoryMapper.class);
        UUID contactId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Instant cutoff = Instant.parse("2026-09-11T03:00:00Z");
        when(contacts.findCreatedBy(contactId)).thenReturn(Optional.of(ownerId));
        when(states.findByOwnerAndContact(ownerId, contactId)).thenReturn(Optional.empty());
        MessageEntity before = message(UUID.randomUUID(), Instant.parse("2026-09-11T02:00:00Z"),
                "before");
        when(memory.listInboundMessagesByCursor(eq(ownerId), eq(contactId),
                any(), any(), eq(cutoff), eq(50))).thenReturn(List.of(before));
        when(memory.listActiveObservations(ownerId, contactId, 100)).thenReturn(List.of());
        when(memory.findCurrentProfile(ownerId, contactId)).thenReturn(null);
        when(memory.listActiveFacts(ownerId, contactId, 100)).thenReturn(List.of());
        when(memory.listActiveLabels(ownerId, contactId, 100)).thenReturn(List.of());
        when(memory.listStableTopics(ownerId, contactId, 20)).thenReturn(List.of());
        when(memory.listCallTranscripts(ownerId, contactId, 10)).thenReturn(List.of());

        ContactMemoryModels.Context context = new ContactMemoryContextService(
                contacts, states, memory).load(ownerId, contactId, cutoff);

        assertThat(context.inboundMessages()).allMatch(item -> !item.getOccurredAt().isAfter(cutoff));
        assertThat(context.inputCursor()).isNull();
        assertThat(context.outputCursor()).contains("|" + before.getId());
        verify(memory).listInboundMessagesByCursor(eq(ownerId), eq(contactId),
                eq(null), eq(null), eq(cutoff), eq(50));
    }

    @Test
    void contextRejectsOwnerMismatchBeforeReadingMemory() {
        ContactMapper contacts = mock(ContactMapper.class);
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryMapper memory = mock(ContactMemoryMapper.class);
        UUID contactId = UUID.randomUUID();
        UUID requestedOwner = UUID.randomUUID();
        UUID actualOwner = UUID.randomUUID();
        when(contacts.findCreatedBy(contactId)).thenReturn(Optional.of(actualOwner));

        assertThatThrownBy(() -> new ContactMemoryContextService(contacts, states, memory)
                .load(requestedOwner, contactId, Instant.parse("2026-09-11T03:00:00Z")))
                .isInstanceOf(ContactMemoryModels.ValidationException.class)
                .hasMessageContaining("OWNER_MISMATCH");
        org.mockito.Mockito.verifyNoInteractions(memory);
    }

    @Test
    void contextAppliesMessageAndTotalCharacterBudgets() {
        ContactMapper contacts = mock(ContactMapper.class);
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryMapper memory = mock(ContactMemoryMapper.class);
        UUID contactId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Instant cutoff = Instant.parse("2026-09-11T03:00:00Z");
        when(contacts.findCreatedBy(contactId)).thenReturn(Optional.of(ownerId));
        when(states.findByOwnerAndContact(ownerId, contactId)).thenReturn(Optional.empty());
        when(memory.listInboundMessagesByCursor(eq(ownerId), eq(contactId),
                any(), any(), eq(cutoff), eq(50))).thenReturn(
                java.util.stream.IntStream.range(0, 60)
                        .mapToObj(index -> message(UUID.randomUUID(),
                                cutoff.minusSeconds(index + 1), "x".repeat(5000)))
                        .toList());
        when(memory.listActiveObservations(ownerId, contactId, 100)).thenReturn(List.of());
        when(memory.findCurrentProfile(ownerId, contactId)).thenReturn(null);
        when(memory.listActiveFacts(ownerId, contactId, 100)).thenReturn(List.of());
        when(memory.listActiveLabels(ownerId, contactId, 100)).thenReturn(List.of());
        when(memory.listStableTopics(ownerId, contactId, 20)).thenReturn(List.of());
        when(memory.listCallTranscripts(ownerId, contactId, 10)).thenReturn(List.of());

        ContactMemoryModels.Context context = new ContactMemoryContextService(
                contacts, states, memory).load(ownerId, contactId, cutoff);

        assertThat(context.inboundMessages()).hasSize(12);
        assertThat(context.inboundMessages()).allMatch(item -> item.getBodyText().length() <= 4000);
        assertThat(context.inboundMessages().stream()
                .mapToInt(item -> item.getBodyText().length()).sum()).isLessThanOrEqualTo(50_000);
    }

    private static MessageEntity message(UUID id, Instant occurredAt, String body) {
        MessageEntity message = new MessageEntity();
        message.setId(id);
        message.setDirection("inbound");
        message.setOccurredAt(occurredAt);
        message.setBodyText(body);
        return message;
    }
}
