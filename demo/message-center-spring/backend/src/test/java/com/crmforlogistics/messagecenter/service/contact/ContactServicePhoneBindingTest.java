package com.crmforlogistics.messagecenter.service.contact;

import com.crmforlogistics.messagecenter.dto.response.PhoneContactBindingResponse;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppAccountResolver;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContactServicePhoneBindingTest {

    private final ContactIdentityMapper identityMapper = mock(ContactIdentityMapper.class);
    private final ContactService service = new ContactService(
            mock(ContactMapper.class), identityMapper,
            mock(ConversationMapper.class), mock(MessageMapper.class),
            mock(ChatAppAccountResolver.class));

    @Test
    void duplicatePhoneReturnsExistingIdentityWithoutInserting() {
        UUID existingContactId = UUID.randomUUID();
        ContactIdentityEntity existing = new ContactIdentityEntity();
        existing.setContactId(existingContactId);
        existing.setIdentityValue("phone:60123456789");
        existing.setDisplayName("Alice");
        when(identityMapper.findByNormalizedValue("phone", "60123456789"))
                .thenReturn(Optional.of(existing));

        PhoneContactBindingResponse result =
                service.bindPhone(UUID.randomUUID().toString(), "Bob", "+60 123-456789");

        assertThat(result.contactId()).isEqualTo(existingContactId.toString());
        assertThat(result.phonePointId()).isEqualTo("phone:60123456789");
        assertThat(result.displayName()).isEqualTo("Alice");
        verify(identityMapper, never()).insert(any(ContactIdentityEntity.class));
    }

    @Test
    void newPhoneInsertsIdentity() {
        UUID contactId = UUID.randomUUID();
        when(identityMapper.findByNormalizedValue("phone", "60123456789"))
                .thenReturn(Optional.empty());

        PhoneContactBindingResponse result =
                service.bindPhone(contactId.toString(), "Bob", "+60 123-456789");

        assertThat(result.contactId()).isEqualTo(contactId.toString());
        assertThat(result.phonePointId()).isEqualTo("phone:60123456789");
        assertThat(result.displayName()).isEqualTo("Bob");
        verify(identityMapper).insert(any(ContactIdentityEntity.class));
    }
}
