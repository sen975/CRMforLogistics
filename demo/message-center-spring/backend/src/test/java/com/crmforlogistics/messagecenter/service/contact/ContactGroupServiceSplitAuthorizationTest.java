package com.crmforlogistics.messagecenter.service.contact;

import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContactGroupServiceSplitAuthorizationTest {

    @Test
    void splitUsesAccessibleContactAndAttributesTheCopyToTheActor() {
        UUID sourceContactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID sourceOwnerId = UUID.randomUUID();

        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(identityId);
        identity.setContactId(sourceContactId);

        ContactEntity source = new ContactEntity();
        source.setId(sourceContactId);
        source.setOwnerUserId(sourceOwnerId);

        ContactMapper contacts = mock(ContactMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        when(identities.selectById(identityId)).thenReturn(identity);
        when(contacts.findAccessibleById(sourceContactId, actorId, false))
                .thenReturn(Optional.of(source));
        when(identities.updateContactIdForIdentity(any(), eq(identityId))).thenReturn(1);

        ContactGroupService service = new ContactGroupService(contacts, identities);

        UUID newContactId = service.split(identityId, "拆分联系人", actorId);

        assertThat(newContactId).isNotEqualTo(sourceContactId);
        var inserted = org.mockito.ArgumentCaptor.forClass(ContactEntity.class);
        verify(contacts).insert(inserted.capture());
        assertThat(inserted.getValue().getCreatedBy()).isEqualTo(actorId);
    }

    @Test
    void splitRejectsIdentityWhenContactIsNotAccessible() {
        UUID sourceContactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(identityId);
        identity.setContactId(sourceContactId);

        ContactMapper contacts = mock(ContactMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        when(identities.selectById(identityId)).thenReturn(identity);
        when(contacts.findAccessibleById(sourceContactId, actorId, false))
                .thenReturn(Optional.empty());

        ContactGroupService service = new ContactGroupService(contacts, identities);

        assertThatThrownBy(() -> service.split(identityId, "拆分联系人", actorId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Contact not found: " + sourceContactId);
    }
}
