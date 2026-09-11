package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class ContactMemoryTriggerServiceTest {

    @Test
    void repeatedInboundMessagesMergeIntoOneDirtyState() {
        ContactMapper contacts = mock(ContactMapper.class);
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        UUID contactId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Instant first = Instant.parse("2026-09-11T01:00:00Z");
        Instant second = Instant.parse("2026-09-11T02:00:00Z");
        when(contacts.findCreatedBy(contactId)).thenReturn(Optional.of(ownerId));

        ContactMemoryTriggerService service = new ContactMemoryTriggerService(contacts, states);

        service.markInboundPersisted(contactId, first);
        service.markInboundPersisted(contactId, second);

        verify(states).markDirty(contactId, ownerId, first);
        verify(states).markDirty(contactId, ownerId, second);
    }

    @Test
    void contactWithoutCreatedByDoesNotCreateMemoryState() {
        ContactMapper contacts = mock(ContactMapper.class);
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        UUID contactId = UUID.randomUUID();
        when(contacts.findCreatedBy(contactId)).thenReturn(Optional.empty());

        new ContactMemoryTriggerService(contacts, states)
                .markInboundPersisted(contactId, Instant.parse("2026-09-11T01:00:00Z"));

        verify(states, never()).markDirty(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
