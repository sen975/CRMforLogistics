package com.crmforlogistics.messagecenter.service.contact;

import com.crmforlogistics.messagecenter.dto.request.CreateChannelContactRequest;
import com.crmforlogistics.messagecenter.dto.request.SearchMode;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChannelAddressBookServiceTest {

    @Test
    void pageIsOwnerScopedAndClampsRequestedSize() {
        ContactMapper contacts = mock(ContactMapper.class);
        UUID owner = UUID.randomUUID();
        when(contacts.listAddressBookByOwner(owner, "email", "buyer", false, 101, 0)).thenReturn(List.of());

        var result = service(contacts, mock(ContactIdentityMapper.class), mock(ChannelAccountMapper.class))
                .page(owner, "email", " buyer ", SearchMode.CONTACT, 1, 500);

        assertThat(result.items()).isEmpty();
        assertThat(result.size()).isEqualTo(100);
        verify(contacts).listAddressBookByOwner(owner, "email", "buyer", false, 101, 0);
    }

    @Test
    void createsManualContactInActiveAccountScopeAndNeverAcceptsAnOwnerFromInput() {
        ContactMapper contacts = mock(ContactMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        UUID owner = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        account.setAuthStatus("active");
        when(accounts.findByOwnerAndChannelType(owner, "email")).thenReturn(List.of(account));
        when(identities.findByNormalizedValueInScope("email", accountId.toString(), "buyer@example.test"))
                .thenReturn(Optional.empty());
        when(identities.insertIfAbsent(any(ContactIdentityEntity.class))).thenReturn(1);

        var created = service(contacts, identities, accounts).createManual(owner,
                new CreateChannelContactRequest("email", "Buyer", "BUYER@example.test"));

        assertThat(created.channelType()).isEqualTo("email");
        assertThat(created.address()).isEqualTo("BUYER@example.test");
        var contactCaptor = org.mockito.ArgumentCaptor.forClass(ContactEntity.class);
        var identityCaptor = org.mockito.ArgumentCaptor.forClass(ContactIdentityEntity.class);
        verify(contacts).insert(contactCaptor.capture());
        verify(identities).insertIfAbsent(identityCaptor.capture());
        assertThat(contactCaptor.getValue().getCreatedBy()).isEqualTo(owner);
        assertThat(contactCaptor.getValue().getOwnerUserId()).isNull();
        assertThat(identityCaptor.getValue().getIdentityScope()).isEqualTo(accountId.toString());
        assertThat(identityCaptor.getValue().getNormalizedValue()).isEqualTo("buyer@example.test");
        assertThat(identityCaptor.getValue().getSource()).isEqualTo("manual");
    }

    @Test
    void rejectsDuplicateAddressWithinTheSameOwnerScope() {
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        UUID owner = UUID.randomUUID();
        when(accounts.findByOwnerAndChannelType(owner, "chatapp")).thenReturn(List.of());
        when(identities.findByNormalizedValueInScope("chatapp", owner.toString(), "60123456789"))
                .thenReturn(Optional.of(new ContactIdentityEntity()));

        assertThatThrownBy(() -> service(mock(ContactMapper.class), identities, accounts)
                .createManual(owner, new CreateChannelContactRequest("chatapp", "Buyer", "+60 12-345 6789")))
                .isInstanceOf(ChannelAddressBookException.class)
                .hasMessage("CONTACT_IDENTITY_ALREADY_EXISTS");
    }

    @Test
    void translatesConcurrentIdentityInsertConflict() {
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        UUID owner = UUID.randomUUID();
        when(accounts.findByOwnerAndChannelType(owner, "email")).thenReturn(List.of());
        when(identities.findByNormalizedValueInScope("email", owner.toString(), "buyer@example.test"))
                .thenReturn(Optional.empty());
        when(identities.insertIfAbsent(any(ContactIdentityEntity.class))).thenReturn(0);

        assertThatThrownBy(() -> service(mock(ContactMapper.class), identities, accounts)
                .createManual(owner, new CreateChannelContactRequest(
                        "email", "Buyer", "buyer@example.test")))
                .isInstanceOf(ChannelAddressBookException.class)
                .hasMessage("CONTACT_IDENTITY_ALREADY_EXISTS");
    }

    @Test
    void onlyActivityFreeManualOwnedContactCanBeDeleted() {
        ContactMapper contacts = mock(ContactMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        UUID owner = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactEntity contact = new ContactEntity();
        contact.setId(contactId);
        contact.setOwnerUserId(owner);
        when(contacts.findByIdAndOwner(contactId, owner)).thenReturn(Optional.of(contact));
        when(identities.countActiveByContactId(contactId)).thenReturn(1);
        when(identities.countNonManualByContactId(contactId)).thenReturn(0);
        when(contacts.hasBusinessActivity(owner, contactId)).thenReturn(false);
        when(contacts.deleteOwned(owner, contactId)).thenReturn(1);

        service(contacts, identities, mock(ChannelAccountMapper.class)).deleteManual(owner, contactId);

        verify(identities).deleteByContactId(contactId);
        verify(contacts).deleteOwned(owner, contactId);
    }

    @Test
    void contactWithActivityCannotBeDeleted() {
        ContactMapper contacts = mock(ContactMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        UUID owner = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        when(contacts.findByIdAndOwner(contactId, owner)).thenReturn(Optional.of(new ContactEntity()));
        when(identities.countActiveByContactId(contactId)).thenReturn(1);
        when(identities.countNonManualByContactId(contactId)).thenReturn(0);
        when(contacts.hasBusinessActivity(owner, contactId)).thenReturn(true);

        assertThatThrownBy(() -> service(contacts, identities, mock(ChannelAccountMapper.class))
                .deleteManual(owner, contactId))
                .isInstanceOf(ChannelAddressBookException.class)
                .hasMessage("CONTACT_HAS_ACTIVITY");
        verify(contacts, never()).deleteOwned(any(), any());
    }

    @Test
    void inboundResolutionUsesOwnedAccountScopeAndCreatesSyncedIdentity() {
        ContactMapper contacts = mock(ContactMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        UUID owner = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(accountId);
        account.setOwnerUserId(owner);
        account.setChannelType("chatapp");
        account.setAuthStatus("active");
        when(accounts.findByIdAndOwner(accountId, owner)).thenReturn(account);
        when(identities.findByNormalizedValueInScope("chatapp", accountId.toString(), "60123456789"))
                .thenReturn(Optional.empty());
        when(identities.insertIfAbsent(any(ContactIdentityEntity.class))).thenReturn(1);

        var result = service(contacts, identities, accounts).resolveOrCreateInbound(
                owner, "chatapp", accountId, "+60 12-345 6789", "Buyer");

        assertThat(result.created()).isTrue();
        var identityCaptor = org.mockito.ArgumentCaptor.forClass(ContactIdentityEntity.class);
        verify(identities).insertIfAbsent(identityCaptor.capture());
        assertThat(identityCaptor.getValue().getIdentityScope()).isEqualTo(accountId.toString());
        assertThat(identityCaptor.getValue().getSource()).isEqualTo("synced");
    }

    @Test
    void phoneResolutionReusesExistingOwnerScopedIdentity() {
        ContactMapper contacts = mock(ContactMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        UUID owner = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(identityId);
        identity.setContactId(contactId);
        when(identities.findByNormalizedValueInScope("phone", owner.toString(), "8613800138000"))
                .thenReturn(Optional.of(identity));
        when(contacts.findByIdAndOwner(contactId, owner)).thenReturn(Optional.of(new ContactEntity()));

        var result = service(contacts, identities, mock(ChannelAccountMapper.class))
                .resolvePhone(owner, null, "+86 138 0013 8000", null);

        assertThat(result.contactId()).isEqualTo(contactId);
        assertThat(result.identityId()).isEqualTo(identityId);
        assertThat(result.created()).isFalse();
        verify(contacts, never()).insert(any(ContactEntity.class));
    }

    @Test
    void phoneResolutionCreatesNumberNamedContactWhenNoIdentityExists() {
        ContactMapper contacts = mock(ContactMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        UUID owner = UUID.randomUUID();
        when(identities.findByNormalizedValueInScope("phone", owner.toString(), "8613800138000"))
                .thenReturn(Optional.empty());
        when(identities.insertIfAbsent(any(ContactIdentityEntity.class))).thenReturn(1);

        var result = service(contacts, identities, mock(ChannelAccountMapper.class))
                .resolvePhone(owner, null, "+86 138 0013 8000", null);

        assertThat(result.created()).isTrue();
        var contactCaptor = org.mockito.ArgumentCaptor.forClass(ContactEntity.class);
        verify(contacts).insert(contactCaptor.capture());
        assertThat(contactCaptor.getValue().getCreatedBy()).isEqualTo(owner);
        assertThat(contactCaptor.getValue().getOwnerUserId()).isNull();
        assertThat(contactCaptor.getValue().getDisplayName()).isEqualTo("8613800138000");
    }

    @Test
    void phoneResolutionAddsMissingIdentityToExistingOwnerContact() {
        ContactMapper contacts = mock(ContactMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        UUID owner = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        when(contacts.findByIdAndOwner(contactId, owner)).thenReturn(Optional.of(new ContactEntity()));
        when(identities.findByNormalizedValueInScope("phone", owner.toString(), "8613800138000"))
                .thenReturn(Optional.empty());
        when(identities.insertIfAbsent(any(ContactIdentityEntity.class))).thenReturn(1);

        var result = service(contacts, identities, mock(ChannelAccountMapper.class))
                .resolvePhone(owner, contactId, "+86 138 0013 8000", "Buyer");

        assertThat(result.contactId()).isEqualTo(contactId);
        assertThat(result.created()).isFalse();
        var identityCaptor = org.mockito.ArgumentCaptor.forClass(ContactIdentityEntity.class);
        verify(identities).insertIfAbsent(identityCaptor.capture());
        assertThat(identityCaptor.getValue().getContactId()).isEqualTo(contactId);
        assertThat(identityCaptor.getValue().getIdentityScope()).isEqualTo(owner.toString());
    }

    @Test
    void phoneResolutionRejectsCrossOwnerContact() {
        ContactMapper contacts = mock(ContactMapper.class);
        UUID owner = UUID.randomUUID();
        UUID foreignContact = UUID.randomUUID();
        when(contacts.findByIdAndOwner(foreignContact, owner)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(contacts, mock(ContactIdentityMapper.class), mock(ChannelAccountMapper.class))
                .resolvePhone(owner, foreignContact, "8613800138000", null))
                .isInstanceOf(ChannelAddressBookException.class)
                .hasMessage("RESOURCE_NOT_FOUND");
    }

    @Test
    void phoneResolutionRereadsConcurrentWinner() {
        ContactMapper contacts = mock(ContactMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        UUID owner = UUID.randomUUID();
        UUID winnerContact = UUID.randomUUID();
        UUID winnerIdentity = UUID.randomUUID();
        ContactIdentityEntity winner = new ContactIdentityEntity();
        winner.setId(winnerIdentity);
        winner.setContactId(winnerContact);
        when(identities.findByNormalizedValueInScope("phone", owner.toString(), "8613800138000"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(identities.insertIfAbsent(any(ContactIdentityEntity.class))).thenReturn(0);
        when(contacts.findByIdAndOwner(winnerContact, owner)).thenReturn(Optional.of(new ContactEntity()));

        var result = service(contacts, identities, mock(ChannelAccountMapper.class))
                .resolvePhone(owner, null, "8613800138000", null);

        assertThat(result.contactId()).isEqualTo(winnerContact);
        assertThat(result.identityId()).isEqualTo(winnerIdentity);
        assertThat(result.created()).isFalse();
        verify(contacts).deleteOwned(eq(owner), any());
    }

    private static ChannelAddressBookService service(ContactMapper contacts,
                                                       ContactIdentityMapper identities,
                                                       ChannelAccountMapper accounts) {
        return new ChannelAddressBookService(contacts, identities, accounts,
                mock(ContactTagMatchResolver.class));
    }
}
