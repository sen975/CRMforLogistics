package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.entity.ContactMemoryStateEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEvidenceEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;
import com.crmforlogistics.messagecenter.dto.response.ContactTagResponse;
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
                Instant.parse("2026-09-11T02:30:00Z"), "before");
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
    void lateInsertedMessageUsesReceivedAtCursorEvenWhenOccurredAtIsOlder() {
        ContactMapper contacts = mock(ContactMapper.class);
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryMapper memory = mock(ContactMemoryMapper.class);
        UUID contactId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID cursorMessageId = UUID.randomUUID();
        Instant receivedCursor = Instant.parse("2026-09-11T02:00:00Z");
        Instant cutoff = Instant.parse("2026-09-11T03:00:00Z");
        when(contacts.findCreatedBy(contactId)).thenReturn(Optional.of(ownerId));
        ContactMemoryStateEntity state = new ContactMemoryStateEntity();
        state.setLastSuccessCursor(receivedCursor + "|" + cursorMessageId);
        when(states.findByOwnerAndContact(ownerId, contactId)).thenReturn(Optional.of(state));
        MessageEntity lateInserted = message(UUID.randomUUID(),
                Instant.parse("2026-09-10T23:00:00Z"),
                Instant.parse("2026-09-11T02:30:00Z"), "late");
        when(memory.listInboundMessagesByCursor(eq(ownerId), eq(contactId),
                eq(receivedCursor), eq(cursorMessageId), eq(cutoff), eq(50)))
                .thenReturn(List.of(lateInserted));
        when(memory.listActiveObservations(ownerId, contactId, 100)).thenReturn(List.of());
        when(memory.findCurrentProfile(ownerId, contactId)).thenReturn(null);
        when(memory.listActiveFacts(ownerId, contactId, 100)).thenReturn(List.of());
        when(memory.listActiveLabels(ownerId, contactId, 100)).thenReturn(List.of());
        when(memory.listStableTopics(ownerId, contactId, 20)).thenReturn(List.of());
        when(memory.listCallTranscripts(ownerId, contactId, 10)).thenReturn(List.of());

        ContactMemoryModels.Context context = new ContactMemoryContextService(
                contacts, states, memory).load(ownerId, contactId, cutoff);

        assertThat(context.inboundMessages()).hasSize(1);
        assertThat(context.inboundMessages().get(0).getId()).isEqualTo(lateInserted.getId());
        assertThat(context.inboundMessages().get(0).getBodyText()).isEqualTo("late");
        assertThat(context.outputCursor()).isEqualTo(
                lateInserted.getReceivedAt() + "|" + lateInserted.getId());
        verify(memory).listInboundMessagesByCursor(eq(ownerId), eq(contactId),
                eq(receivedCursor), eq(cursorMessageId), eq(cutoff), eq(50));
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
                                cutoff.minusSeconds(index + 1),
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

    @Test
    void contextCarriesHistoricalObservationEvidenceForCrossRoundDeduplication() {
        ContactMapper contacts = mock(ContactMapper.class);
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryMapper memory = mock(ContactMemoryMapper.class);
        UUID contactId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID observationId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        Instant cutoff = Instant.parse("2026-09-11T03:00:00Z");
        when(contacts.findCreatedBy(contactId)).thenReturn(Optional.of(ownerId));
        when(states.findByOwnerAndContact(ownerId, contactId)).thenReturn(Optional.empty());
        when(memory.listInboundMessagesByCursor(eq(ownerId), eq(contactId), any(), any(), eq(cutoff), eq(50)))
                .thenReturn(List.of());
        ContactMemoryObservationEntity observation = new ContactMemoryObservationEntity();
        observation.setId(observationId);
        observation.setEvidenceCount(1);
        when(memory.listActiveObservations(ownerId, contactId, 100)).thenReturn(List.of(observation));
        ContactMemoryObservationEvidenceEntity evidence = new ContactMemoryObservationEvidenceEntity();
        evidence.setEvidenceId(evidenceId);
        evidence.setEvidenceType(ContactMemoryModels.EvidenceType.MESSAGE.name());
        when(memory.listObservationEvidence(observationId, contactId, ownerId)).thenReturn(List.of(evidence));
        when(memory.findCurrentProfile(ownerId, contactId)).thenReturn(null);
        when(memory.listActiveFacts(ownerId, contactId, 100)).thenReturn(List.of());
        when(memory.listActiveLabels(ownerId, contactId, 100)).thenReturn(List.of());
        when(memory.listStableTopics(ownerId, contactId, 20)).thenReturn(List.of());
        when(memory.listCallTranscripts(ownerId, contactId, 10)).thenReturn(List.of());

        ContactMemoryModels.Context context = new ContactMemoryContextService(
                contacts, states, memory).load(ownerId, contactId, cutoff);

        assertThat(context.observations()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getEvidenceCount()).isEqualTo(1);
                    assertThat(item.getEvidence()).singleElement()
                            .extracting(ContactMemoryObservationEvidenceEntity::getEvidenceId)
                            .isEqualTo(evidenceId);
                });
    }

    @Test
    void contextCarriesManualTagsAsAnImmutableOwnerScopedReadOnlyList() {
        ContactMapper contacts = mock(ContactMapper.class);
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryMapper memory = mock(ContactMemoryMapper.class);
        ContactTagMapper tags = mock(ContactTagMapper.class);
        UUID contactId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Instant cutoff = Instant.parse("2026-09-11T03:00:00Z");
        UUID tagId = UUID.randomUUID();
        when(contacts.findCreatedBy(contactId)).thenReturn(Optional.of(ownerId));
        when(states.findByOwnerAndContact(ownerId, contactId)).thenReturn(Optional.empty());
        when(memory.listInboundMessagesByCursor(eq(ownerId), eq(contactId), any(), any(), eq(cutoff), eq(50)))
                .thenReturn(List.of());
        when(memory.listActiveObservations(ownerId, contactId, 100)).thenReturn(List.of());
        when(memory.findCurrentProfile(ownerId, contactId)).thenReturn(null);
        when(memory.listActiveFacts(ownerId, contactId, 100)).thenReturn(List.of());
        when(memory.listActiveLabels(ownerId, contactId, 100)).thenReturn(List.of());
        when(memory.listStableTopics(ownerId, contactId, 20)).thenReturn(List.of());
        when(memory.listCallTranscripts(ownerId, contactId, 10)).thenReturn(List.of());
        when(tags.findActiveByContactIdAndOwner(contactId, ownerId))
                .thenReturn(List.of(new ContactTagResponse(tagId, "重要客户", "red")));

        ContactMemoryModels.Context context = new ContactMemoryContextService(
                contacts, states, memory, tags,
                new com.crmforlogistics.messagecenter.config.ContactMemoryConfig(
                        50, 4000, 50000, 20, 1000, 10, 4000,
                        100, 100, 100, 500, 30)).load(ownerId, contactId, cutoff);

        assertThat(context.manualTags()).containsExactly(
                new ContactMemoryModels.ManualTag(tagId, "重要客户", "red"));
        assertThatThrownBy(() -> context.manualTags().add(
                new ContactMemoryModels.ManualTag(UUID.randomUUID(), "不可写", "blue")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static MessageEntity message(UUID id, Instant occurredAt, Instant receivedAt, String body) {
        MessageEntity message = new MessageEntity();
        message.setId(id);
        message.setDirection("inbound");
        message.setOccurredAt(occurredAt);
        message.setReceivedAt(receivedAt);
        message.setBodyText(body);
        return message;
    }
}
