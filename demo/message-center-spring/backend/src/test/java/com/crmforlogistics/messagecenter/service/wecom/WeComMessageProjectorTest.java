package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.entity.WeComPartyEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.WeComPartyMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceConversationMapper;
import com.crmforlogistics.messagecenter.service.contactmemory.ContactMemoryTriggerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComMessageProjectorTest {
    @Test
    void projectsReferenceWithoutPlaintextAndPreservesDirection() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setChannelType("wecom");
        when(accounts.selectSingleActiveByChannelType("wecom")).thenReturn(account);
        when(identities.findByNormalizedValueInScope("wecom", account.getId().toString(), "external"))
                .thenReturn(Optional.empty());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        when(conversations.getOrCreateConversation(any(), any())).thenReturn(conversation);

        WeComMessageProjector projector = new WeComMessageProjector(
                accounts, identities, contacts, conversations, messages);
        WeComMessageProjector.ProjectionResult result = projector.project(
                new WeComMessageProjector.WeComProjectedMessage(
                        "m1", "external", "employee", 100L, "outbound"));

        assertThat(result.inserted()).isTrue();
        ArgumentCaptor<MessageEntity> inserted = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messages).insertWithSequence(inserted.capture());
        assertThat(inserted.getValue().getProviderMessageId()).isEqualTo("m1");
        assertThat(inserted.getValue().getDirection()).isEqualTo("outbound");
        assertThat(inserted.getValue().getBodyText()).isEmpty();
        assertThat(inserted.getValue().getMetadataJsonb()).isEqualTo("{\"wecomReference\":true}");
    }

    @Test
    void inboundDirectReferenceTriggersContactMemoryAfterPersistence() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        ContactMemoryTriggerService memoryTrigger = mock(ContactMemoryTriggerService.class);
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        when(accounts.selectSingleActiveByChannelType("wecom")).thenReturn(account);
        UUID contactId = UUID.randomUUID();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contactId);
        when(messages.findByProviderMessageId(account.getId(), "m-memory-1"))
                .thenReturn(Optional.empty());
        when(identities.findByNormalizedValueInScope(
                "wecom", account.getId().toString(), "external-memory"))
                .thenReturn(Optional.of(identity));
        when(conversations.getOrCreateConversation(any(), any()))
                .thenReturn(new ConversationEntity());

        WeComMessageProjector projector = new WeComMessageProjector(
                accounts, identities, contacts, conversations, messages,
                null, null, null, memoryTrigger);
        projector.project(new WeComMessageProjector.WeComProjectedMessage(
                "m-memory-1", "external-memory", "employee", 100L, "inbound"));

        verify(memoryTrigger).markInboundPersisted(
                eq(contactId), eq(java.time.Instant.ofEpochSecond(100L)));
    }

    @Test
    void projectsTheExternalContactNicknameWhenTheProfileLookupSucceeds() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        WeComExternalContactService externalContacts = mock(WeComExternalContactService.class);
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setChannelType("wecom");
        when(accounts.selectSingleActiveByChannelType("wecom")).thenReturn(account);
        when(identities.findByNormalizedValueInScope("wecom", account.getId().toString(), "external"))
                .thenReturn(Optional.empty());
        when(externalContacts.displayNameForSync("corp", "external")).thenReturn("客户昵称");
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        when(conversations.getOrCreateConversation(any(), any())).thenReturn(conversation);

        WeComMessageProjector projector = new WeComMessageProjector(
                accounts, identities, contacts, conversations, messages, externalContacts);
        projector.project(new WeComMessageProjector.WeComProjectedMessage(
                "m1", "external", "employee", 100L, "inbound", "corp"));

        ArgumentCaptor<ContactEntity> createdContact = ArgumentCaptor.forClass(ContactEntity.class);
        ArgumentCaptor<ContactIdentityEntity> createdIdentity = ArgumentCaptor.forClass(ContactIdentityEntity.class);
        verify(contacts).insert(createdContact.capture());
        verify(identities).insert(createdIdentity.capture());
        assertThat(createdContact.getValue().getDisplayName()).isEqualTo("客户昵称");
        assertThat(createdIdentity.getValue().getDisplayName()).isEqualTo("客户昵称");
    }

    @Test
    void refreshesAnExistingIdentityNameWhenTheMessageWasAlreadyProjected() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        WeComExternalContactService externalContacts = mock(WeComExternalContactService.class);
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setChannelType("wecom");
        when(accounts.selectSingleActiveByChannelType("wecom")).thenReturn(account);
        ContactEntity contact = new ContactEntity();
        contact.setId(UUID.randomUUID());
        contact.setDisplayName("external");
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contact.getId());
        identity.setDisplayName("external");
        when(messages.findByProviderMessageId(account.getId(), "m1"))
                .thenReturn(Optional.of(new MessageEntity()));
        when(identities.findByNormalizedValueInScope("wecom", account.getId().toString(), "external"))
                .thenReturn(Optional.of(identity));
        when(externalContacts.displayNameForSync("corp", "external")).thenReturn("客户昵称");

        WeComMessageProjector projector = new WeComMessageProjector(
                accounts, identities, contacts, conversations, messages, externalContacts);
        WeComMessageProjector.ProjectionResult result = projector.project(
                new WeComMessageProjector.WeComProjectedMessage(
                        "m1", "external", "employee", 100L, "inbound", "corp"));

        assertThat(result.inserted()).isFalse();
        verify(identities).updateDisplayName(identity.getId(), "客户昵称");
        verify(contacts).updateDisplayName(identity.getContactId(), "客户昵称");
    }

    @Test
    void projectsGroupReferenceWithoutCreatingContactIdentity() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        when(accounts.selectSingleActiveByChannelType("wecom")).thenReturn(account);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        when(conversations.getOrCreateSourceConversation(any(), any())).thenReturn(conversation);

        WeComMessageProjector projector = new WeComMessageProjector(
                accounts, identities, contacts, conversations, messages);
        var result = projector.projectGroup(new WeComMessageProjector.WeComProjectedGroupMessage(
                "group-message", UUID.randomUUID(), 100L, "inbound"));

        assertThat(result.inserted()).isTrue();
        verify(identities, never()).insert(any(ContactIdentityEntity.class));
        verify(contacts, never()).insert(any(ContactEntity.class));
        verify(messages).insertWithSequence(any(MessageEntity.class));
    }

    @Test
    void projectsDirectEmployeeMessageIntoContactIdentitySoItAppearsInContacts() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        when(accounts.selectSingleActiveByChannelType("wecom")).thenReturn(account);
        when(messages.findByProviderMessageId(account.getId(), "direct-1")).thenReturn(Optional.empty());
        when(identities.findByNormalizedValueInScope("wecom", account.getId().toString(), "employee-2"))
                .thenReturn(Optional.empty());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        when(conversations.getOrCreateConversation(any(), any())).thenReturn(conversation);

        WeComMessageProjector projector = new WeComMessageProjector(
                accounts, identities, contacts, conversations, messages);
        var result = projector.projectDirect(new WeComMessageProjector.WeComProjectedDirectMessage(
                "direct-1", UUID.randomUUID(), UUID.randomUUID(), "corp-1",
                new WeComMessageProjector.ContactParty("EMPLOYEE", "employee-2"), 100L, "inbound"));

        assertThat(result.inserted()).isTrue();
        verify(contacts).insert(any(ContactEntity.class));
        verify(identities).insert(argThat((ContactIdentityEntity identity) ->
                "employee-2".equals(identity.getIdentityValue())
                        && "employee-2".equals(identity.getDisplayName())));
        verify(conversations).getOrCreateConversation(eq(account.getId()), any());
    }

    @Test
    void inboundDirectMessageTriggersContactMemoryForResolvedContact() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        ContactMemoryTriggerService memoryTrigger = mock(ContactMemoryTriggerService.class);
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        when(accounts.selectSingleActiveByChannelType("wecom")).thenReturn(account);
        when(messages.findByProviderMessageId(account.getId(), "direct-memory-1"))
                .thenReturn(Optional.empty());
        UUID contactId = UUID.randomUUID();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contactId);
        when(identities.findByNormalizedValueInScope(
                "wecom", account.getId().toString(), "external-memory"))
                .thenReturn(Optional.of(identity));
        when(conversations.getOrCreateConversation(any(), any()))
                .thenReturn(new ConversationEntity());

        WeComMessageProjector projector = new WeComMessageProjector(
                accounts, identities, contacts, conversations, messages, null, null, null,
                memoryTrigger);
        projector.projectDirect(new WeComMessageProjector.WeComProjectedDirectMessage(
                "direct-memory-1", UUID.randomUUID(), UUID.randomUUID(), "corp-1",
                new WeComMessageProjector.ContactParty("EXTERNAL_CONTACT", "external-memory"),
                100L, "inbound"));

        verify(memoryTrigger).markInboundPersisted(eq(contactId), eq(java.time.Instant.ofEpochSecond(100L)));
    }

    @Test
    void inboundGroupMessageDoesNotTriggerContactMemory() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        ContactMemoryTriggerService memoryTrigger = mock(ContactMemoryTriggerService.class);
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        when(accounts.selectSingleActiveByChannelType("wecom")).thenReturn(account);
        when(conversations.getOrCreateSourceConversation(any(), any()))
                .thenReturn(new ConversationEntity());

        WeComMessageProjector projector = new WeComMessageProjector(
                accounts, identities, contacts, conversations, messages, null, null, null,
                memoryTrigger);
        projector.projectGroup(new WeComMessageProjector.WeComProjectedGroupMessage(
                "group-memory-1", UUID.randomUUID(), 100L, "inbound"));

        verify(memoryTrigger, never()).markInboundPersisted(any(), any());
    }

    @Test
    void bindsDirectSourceConversationToTheResolvedWeComIdentity() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        WeComSourceConversationMapper sourceConversations = mock(WeComSourceConversationMapper.class);
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        account.setChannelType("wecom");
        UUID sourceId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        when(accounts.selectSingleActiveByChannelType("wecom")).thenReturn(account);
        when(messages.findByProviderMessageId(account.getId(), "direct-bind-1")).thenReturn(Optional.empty());
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(identityId);
        identity.setContactId(UUID.randomUUID());
        identity.setDisplayName("曹舒婷");
        when(identities.findByNormalizedValueInScope("wecom", account.getId().toString(), "external-1"))
                .thenReturn(Optional.of(identity));
        when(conversations.getOrCreateConversation(any(), any())).thenReturn(new ConversationEntity());

        WeComMessageProjector projector = new WeComMessageProjector(
                accounts, identities, contacts, conversations, messages, null, null, sourceConversations);
        projector.projectDirect(new WeComMessageProjector.WeComProjectedDirectMessage(
                "direct-bind-1", sourceId, UUID.randomUUID(), "corp-1",
                new WeComMessageProjector.ContactParty("EXTERNAL_CONTACT", "external-1"), 100L, "inbound"));

        verify(sourceConversations).bindContactIdentity(sourceId, identityId);
    }

    @Test
    void projectsEmployeeNicknameFromPartyProfile() {
        ChannelAccountMapper accounts = mock(ChannelAccountMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        ContactMapper contacts = mock(ContactMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        MessageMapper messages = mock(MessageMapper.class);
        WeComPartyMapper parties = mock(WeComPartyMapper.class);
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(UUID.randomUUID());
        UUID installationId = UUID.randomUUID();
        when(accounts.selectSingleActiveByChannelType("wecom")).thenReturn(account);
        when(messages.findByProviderMessageId(account.getId(), "direct-nickname-1"))
                .thenReturn(Optional.empty());
        when(identities.findByNormalizedValueInScope("wecom", account.getId().toString(), "employee-2"))
                .thenReturn(Optional.empty());
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        when(conversations.getOrCreateConversation(any(), any())).thenReturn(conversation);
        WeComPartyEntity profile = new WeComPartyEntity();
        profile.setInstallationId(installationId);
        profile.setPartyType("EMPLOYEE");
        profile.setProviderPartyId("employee-2");
        profile.setDisplayName("张三");
        when(parties.selectOne(any())).thenReturn(profile);

        WeComMessageProjector projector = new WeComMessageProjector(
                accounts, identities, contacts, conversations, messages, null, parties);
        projector.projectDirect(new WeComMessageProjector.WeComProjectedDirectMessage(
                "direct-nickname-1", UUID.randomUUID(), installationId, "corp-1",
                new WeComMessageProjector.ContactParty("EMPLOYEE", "employee-2"), 100L, "inbound"));

        verify(contacts).insert(argThat((ContactEntity contact) -> "张三".equals(contact.getDisplayName())));
        verify(identities).insert(argThat((ContactIdentityEntity identity) -> "张三".equals(identity.getDisplayName())));
    }
}
