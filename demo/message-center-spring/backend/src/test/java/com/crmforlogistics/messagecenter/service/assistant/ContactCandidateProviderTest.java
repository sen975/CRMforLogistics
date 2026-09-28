package com.crmforlogistics.messagecenter.service.assistant;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContactCandidateProviderTest {

    @Test
    void recentProjectsOnlyOwnerAuthorizedChannelProfiles() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        ContactEntity contact = new ContactEntity();
        contact.setId(contactId);
        contact.setDisplayName("周明");
        contact.setRemark("张江物流 对接人");

        ContactMapper mapper = mock(ContactMapper.class);
        Page<ContactEntity> page = new Page<>(1, ContactCandidateProvider.LIMIT);
        page.setRecords(List.of(contact));
        when(mapper.listForUser(any(), same(userId), isNull(), anyBoolean(), isNull(), isNull(),
                anyBoolean(), isNull(), isNull())).thenReturn(page);

        ContactService contactService = mock(ContactService.class);
        when(contactService.listAuthorizedChannelProfiles(userId, contactId)).thenReturn(List.of(
                new ContactService.AuthorizedChannelProfile("email", "buyer@example.invalid", "采购邮箱", "销售邮箱")));

        ContactCandidates result = new ContactCandidateProvider(mapper, contactService).recent(userId);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("周明");
            assertThat(item.remark()).isEqualTo("张江物流 对接人");
            assertThat(item.channels()).containsExactly(new ContactCandidates.Channel(
                    "email", "buyer@example.invalid", "采购邮箱", "销售邮箱"));
            assertThat(item.toString()).doesNotContain("identityScope", "normalizedValue", "encryptedConfig");
        });
        verify(mapper).listForUser(any(), same(userId), isNull(), anyBoolean(), isNull(), isNull(),
                anyBoolean(), isNull(), isNull());
        verify(contactService).listAuthorizedChannelProfiles(userId, contactId);
    }

    @Test
    void nullUserDoesNotQueryContactsOrChannelProfiles() {
        ContactMapper mapper = mock(ContactMapper.class);
        ContactService contactService = mock(ContactService.class);

        ContactCandidates result = new ContactCandidateProvider(mapper, contactService).recent(null);

        assertThat(result.items()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(mapper, contactService);
    }

    @Test
    void refreshedReferencesAreReauthorizedBeforeCurrentChannelsAreProjected() {
        UUID userId = UUID.randomUUID();
        UUID permitted = UUID.randomUUID();
        UUID revoked = UUID.randomUUID();
        ContactMapper mapper = mock(ContactMapper.class);
        ContactService service = mock(ContactService.class);
        ContactEntity contact = new ContactEntity();
        contact.setId(permitted);
        contact.setDisplayName("守望");
        when(mapper.findAccessibleById(org.mockito.ArgumentMatchers.eq(permitted),
                org.mockito.ArgumentMatchers.eq(userId), anyBoolean()))
                .thenReturn(java.util.Optional.of(contact));
        when(mapper.findAccessibleById(org.mockito.ArgumentMatchers.eq(revoked),
                org.mockito.ArgumentMatchers.eq(userId), anyBoolean()))
                .thenReturn(java.util.Optional.empty());
        when(service.listAuthorizedChannelProfiles(userId, permitted)).thenReturn(List.of(
                new ContactService.AuthorizedChannelProfile("email", "now@example.invalid", null, null)));

        ContactCandidates restored = new ContactCandidateProvider(mapper, service).refresh(userId,
                List.of(ContactCandidates.idOf(revoked), ContactCandidates.idOf(permitted)));

        assertThat(restored.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(ContactCandidates.idOf(permitted));
            assertThat(item.channels()).singleElement().satisfies(channel ->
                    assertThat(channel.identityValue()).isEqualTo("now@example.invalid"));
        });
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
                .listAuthorizedChannelProfiles(userId, revoked);
    }
}
