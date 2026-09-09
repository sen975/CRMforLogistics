package com.crmforlogistics.messagecenter.service.callrecord;

import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import com.crmforlogistics.messagecenter.entity.UserEntity;
import com.crmforlogistics.messagecenter.mapper.CallRecordMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.UserMapper;
import com.crmforlogistics.messagecenter.service.contact.ChannelAddressBookService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CallRecordContactBackfillServiceTest {
    @Test
    void usesCreatedByAndCreatesContactForOrphanRecord() {
        CallRecordMapper records = mock(CallRecordMapper.class);
        UserMapper users = mock(UserMapper.class);
        ChannelAddressBookService addressBooks = mock(ChannelAddressBookService.class);
        UUID owner = UUID.randomUUID();
        CallRecordEntity record = record(null, owner.toString(), null, "phone:8613800138000", 3L);
        UUID contact = UUID.randomUUID();
        when(records.listContactBackfillCandidates(100)).thenReturn(List.of(record));
        UserEntity user = new UserEntity();
        user.setId(owner);
        when(users.findByIdNotDeleted(owner)).thenReturn(Optional.of(user));
        when(addressBooks.resolvePhone(owner, null, "8613800138000", null))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(contact, UUID.randomUUID(), true));
        when(records.updateContactBinding(record.getId(), owner, contact,
                "phone:8613800138000", 3L)).thenReturn(1);

        var result = service(records, users, addressBooks).run(100);

        assertThat(result.processed()).isEqualTo(1);
        assertThat(result.createdContacts()).isEqualTo(1);
        assertThat(result.linkedExistingContacts()).isZero();
        verify(records).updateContactBinding(record.getId(), owner, contact,
                "phone:8613800138000", 3L);
    }

    @Test
    void fallsBackToValidCreatedByWhenOwnerIsMissing() {
        CallRecordMapper records = mock(CallRecordMapper.class);
        UserMapper users = mock(UserMapper.class);
        ChannelAddressBookService addressBooks = mock(ChannelAddressBookService.class);
        UUID owner = UUID.randomUUID();
        CallRecordEntity record = record(null, owner.toString(), null, "phone:8613428277520", 1L);
        UUID contact = UUID.randomUUID();
        when(records.listContactBackfillCandidates(100)).thenReturn(List.of(record));
        UserEntity user = new UserEntity();
        user.setId(owner);
        when(users.findByIdNotDeleted(owner)).thenReturn(Optional.of(user));
        when(addressBooks.resolvePhone(owner, null, "8613428277520", null))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(contact, UUID.randomUUID(), true));
        when(records.updateContactBinding(any(), eq(owner), eq(contact), anyString(), eq(1L))).thenReturn(1);

        var result = service(records, users, addressBooks).run(100);

        assertThat(result.createdContacts()).isEqualTo(1);
        verify(users).findByIdNotDeleted(owner);
    }

    @Test
    void skipsUnownedRecordAndContinuesAfterSingleFailure() {
        CallRecordMapper records = mock(CallRecordMapper.class);
        UserMapper users = mock(UserMapper.class);
        ChannelAddressBookService addressBooks = mock(ChannelAddressBookService.class);
        CallRecordEntity unowned = record(null, "not-a-uuid", null, "phone:8613800138000", 1L);
        CallRecordEntity failing = record(null, UUID.randomUUID().toString(), null, "phone:8613800138001", 1L);
        when(records.listContactBackfillCandidates(100)).thenReturn(List.of(unowned, failing));
        when(users.findByIdNotDeleted(any())).thenReturn(Optional.empty());

        var result = service(records, users, addressBooks).run(100);

        assertThat(result.processed()).isEqualTo(2);
        assertThat(result.skippedUnowned()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        verifyNoInteractions(addressBooks);
    }

    @Test
    void existingContactCreatorIsUsedWhenRecordOwnerIsMissing() {
        CallRecordMapper records = mock(CallRecordMapper.class);
        UserMapper users = mock(UserMapper.class);
        ChannelAddressBookService addressBooks = mock(ChannelAddressBookService.class);
        UUID owner = UUID.randomUUID();
        UUID oldContact = UUID.randomUUID();
        UUID staleCreator = UUID.randomUUID();
        CallRecordEntity record = record(null, staleCreator.toString(), oldContact, "phone:8613800138002", 2L);
        ContactMapper contacts = mock(ContactMapper.class);
        when(records.listContactBackfillCandidates(100)).thenReturn(List.of(record));
        when(contacts.findCreatedBy(oldContact)).thenReturn(Optional.of(owner));
        when(addressBooks.resolvePhone(owner, oldContact, "8613800138002", null))
                .thenReturn(new ChannelAddressBookService.ResolvedContact(oldContact, UUID.randomUUID(), false));
        when(records.updateContactBinding(record.getId(), owner, oldContact,
                "phone:8613800138002", 2L)).thenReturn(1);

        var result = new CallRecordContactBackfillService(records, users,
                new CallRecordContactBackfillWorker(records, addressBooks), contacts).run(100);

        assertThat(result.linkedExistingContacts()).isEqualTo(1);
        assertThat(result.createdContacts()).isZero();
        verify(users, never()).findByIdNotDeleted(staleCreator);
    }

    private static CallRecordContactBackfillService service(CallRecordMapper records, UserMapper users,
                                                             ChannelAddressBookService addressBooks) {
        return new CallRecordContactBackfillService(records, users,
                new CallRecordContactBackfillWorker(records, addressBooks));
    }

    private static CallRecordEntity record(UUID ownerId, String createdBy, UUID contactId, String phone, long version) {
        CallRecordEntity record = new CallRecordEntity();
        record.setId(UUID.randomUUID());
        record.setContactId(contactId);
        record.setOwnerUserId(ownerId);
        record.setCreatedBy(createdBy);
        record.setPhonePointId(phone);
        record.setContactAnchorPointId(phone);
        record.setVersion(version);
        return record;
    }
}
