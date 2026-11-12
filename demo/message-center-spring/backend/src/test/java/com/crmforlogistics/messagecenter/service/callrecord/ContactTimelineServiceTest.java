package com.crmforlogistics.messagecenter.service.callrecord;

import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.mapper.CallRecordMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

class ContactTimelineServiceTest {
    @Test
    void noAuthorizedIdentityStopsBeforeReadingMessagesOrCalls() {
        UUID ownerId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        CallRecordMapper calls = mock(CallRecordMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        when(identities.findByContactIdAndOwner(contactId, ownerId)).thenReturn(List.of());

        CallRecordException failure = assertThrows(CallRecordException.class,
                () -> new ContactTimelineService(calls, messages, identities)
                        .timeline(ownerId, contactId, null, 20));

        assertEquals("CONTACT_NOT_FOUND", failure.code());
        verify(identities).findByContactIdAndOwner(contactId, ownerId);
        verify(messages, never()).listByContactAndOwner(any(), any(), anyInt());
        verify(calls, never()).listByOwnerAndContact(any(), any());
        verify(calls, never()).listByOwnerAndAnchors(any(), any());
    }

    @Test
    void includesOwnerScopedCallRecordsMatchedByPhoneAnchorWhenContactIdWasNotBackfilled() {
        UUID ownerId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        CallRecordMapper calls = mock(CallRecordMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ContactIdentityEntity phone = new ContactIdentityEntity();
        phone.setContactId(contactId);
        phone.setChannelType("phone");
        phone.setNormalizedValue("13800138000");
        when(identities.findByContactIdAndOwner(contactId, ownerId)).thenReturn(List.of(phone));
        when(messages.listByContactAndOwner(ownerId, contactId, 200)).thenReturn(List.of());
        CallRecordEntity record = new CallRecordEntity();
        record.setId(UUID.randomUUID());
        record.setOwnerUserId(ownerId);
        record.setContactId(null);
        record.setContactAnchorPointId("phone:13800138000");
        record.setPhonePointId("phone:13800138000");
        record.setOccurredAt(Instant.parse("2026-09-07T00:00:00Z"));
        record.setDirection("inbound");
        record.setAudioDurationSeconds(1.0);
        record.setTranscriptionState("completed");
        when(calls.listByOwnerAndContact(ownerId, contactId)).thenReturn(List.of());
        when(calls.listByOwnerAndAnchors(ownerId, java.util.Set.of("phone:13800138000")))
                .thenReturn(List.of(record));

        var response = new ContactTimelineService(calls, messages, identities)
                .timeline(ownerId, contactId, null, 100);

        assertEquals(1, response.itemCount());
        assertEquals("callRecord", response.items().get(0).type());
        assertEquals(record.getId().toString(), response.items().get(0).payload().get("id"));
    }
}
