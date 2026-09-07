package com.crmforlogistics.messagecenter.service.contact;

import com.crmforlogistics.messagecenter.dto.request.ContactTagsRequest;
import com.crmforlogistics.messagecenter.dto.response.ContactTagResponse;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContactGroupServiceTagsTest {

    @Test
    void replacesTagsAfterCheckingContactAccess() {
        UUID contactId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ContactMapper contacts = mock(ContactMapper.class);
        ContactTagMapper tags = mock(ContactTagMapper.class);
        ContactEntity contact = new ContactEntity();
        contact.setOwnerUserId(userId);
        when(contacts.findByIdAndOwner(contactId, userId))
                .thenReturn(Optional.of(contact));
        when(tags.findActiveByNameAndOwner("重点跟进", userId)).thenReturn(
                Optional.of(new ContactTagResponse(UUID.randomUUID(), "重点跟进", "blue")));

        ContactGroupService service = new ContactGroupService(contacts,
                mock(ContactIdentityMapper.class), tags);

        service.updateTags(contactId, List.of(
                new ContactTagsRequest.ContactTagInput("重点跟进", "red"),
                new ContactTagsRequest.ContactTagInput("重点跟进", "red")), userId);

        verify(contacts).findByIdAndOwner(contactId, userId);
        verify(tags).deleteByContactIdAndOwner(contactId, userId);
        verify(tags).insertTaggingForOwner(eq(contactId), any(UUID.class), eq(userId));
    }

    @Test
    void rejectsAnInaccessibleContactBeforeChangingTags() {
        UUID contactId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ContactMapper contacts = mock(ContactMapper.class);
        ContactTagMapper tags = mock(ContactTagMapper.class);
        when(contacts.findByIdAndOwner(contactId, userId)).thenReturn(Optional.empty());

        ContactGroupService service = new ContactGroupService(contacts,
                mock(ContactIdentityMapper.class), tags);

        assertThatThrownBy(() -> service.updateTags(contactId, List.of(), userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Contact not found");
    }
}
