package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComMessageProjector {
    private final ChannelAccountMapper accountMapper;
    private final ContactIdentityMapper identities;
    private final ContactMapper contacts;
    private final ConversationMapper conversations;
    private final MessageMapper messages;
    private final WeComExternalContactService externalContacts;
    private final WeComPartyMapper parties;
    private final WeComSourceConversationMapper sourceConversations;
    private final ContactMemoryTriggerService contactMemoryTriggerService;

    @Autowired
    public WeComMessageProjector(ChannelAccountMapper channelAccounts,
                                 ContactIdentityMapper identities,
                                 ContactMapper contacts,
                                 ConversationMapper conversations,
                                 MessageMapper messages,
                                 WeComExternalContactService externalContacts,
                                 WeComPartyMapper parties,
                                 WeComSourceConversationMapper sourceConversations,
                                 ContactMemoryTriggerService contactMemoryTriggerService) {
        this.accountMapper = channelAccounts;
        this.identities = identities;
        this.contacts = contacts;
        this.conversations = conversations;
        this.messages = messages;
        this.externalContacts = externalContacts;
        this.parties = parties;
        this.sourceConversations = sourceConversations;
        this.contactMemoryTriggerService = contactMemoryTriggerService;
    }

    public WeComMessageProjector(ChannelAccountMapper channelAccounts,
                          ContactIdentityMapper identities,
                          ContactMapper contacts,
                          ConversationMapper conversations,
                          MessageMapper messages) {
        this(channelAccounts, identities, contacts, conversations, messages, null, null, null, null);
    }

    public WeComMessageProjector(ChannelAccountMapper channelAccounts,
                                 ContactIdentityMapper identities,
                                 ContactMapper contacts,
                                 ConversationMapper conversations,
                                 MessageMapper messages,
                                 WeComExternalContactService externalContacts) {
        this(channelAccounts, identities, contacts, conversations, messages, externalContacts, null, null, null);
    }

    public WeComMessageProjector(ChannelAccountMapper channelAccounts,
                                 ContactIdentityMapper identities,
                                 ContactMapper contacts,
                                 ConversationMapper conversations,
                                 MessageMapper messages,
                                 WeComExternalContactService externalContacts,
                                 WeComPartyMapper parties) {
        this(channelAccounts, identities, contacts, conversations, messages, externalContacts, parties, null, null);
    }

    public WeComMessageProjector(ChannelAccountMapper channelAccounts,
                                 ContactIdentityMapper identities,
                                 ContactMapper contacts,
                                 ConversationMapper conversations,
                                 MessageMapper messages,
                                 WeComExternalContactService externalContacts,
                                 WeComPartyMapper parties,
                                 WeComSourceConversationMapper sourceConversations) {
        this(channelAccounts, identities, contacts, conversations, messages, externalContacts, parties,
                sourceConversations, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectionResult project(WeComProjectedMessage item) {
        requireValid(item);
        ChannelAccountEntity account = accountMapper.selectSingleActiveByChannelType("wecom");
        if (messages.findByProviderMessageId(account.getId(), item.msgid()).isPresent()) {
            // 去重消息也要经过 identity 刷新，否则首次上游权限失败后会永久显示 ID。
            findOrCreateIdentity(account, item.externalUserId(), item.authCorpId());
            return new ProjectionResult(false);
        }
        ContactIdentityEntity identity = findOrCreateIdentity(account, item.externalUserId(), item.authCorpId());
        ConversationEntity conversation = conversations.getOrCreateConversation(account.getId(), identity.getId());
        Instant occurredAt = Instant.ofEpochSecond(item.sendTime());
        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setChannelAccountId(account.getId());
        message.setConversationId(conversation.getId());
        message.setProviderMessageId(item.msgid());
        message.setDirection(item.direction());
        message.setMessageKind("text");
        message.setBodyText("");
        message.setOccurredAt(occurredAt);
        message.setCountsAsUnread("inbound".equals(item.direction()));
        message.setCurrentStatus("outbound".equals(item.direction()) ? "sent" : "delivered");
        message.setCurrentStatusAt(occurredAt);
        message.setMetadataJsonb("{\"wecomReference\":true}");
        messages.insertWithSequence(message);
        if ("inbound".equals(item.direction()) && contactMemoryTriggerService != null) {
            contactMemoryTriggerService.markInboundPersisted(identity.getContactId(), message.getId(),
                    message.getIngestSequence(), occurredAt, message.getReceivedAt());
        }
        return new ProjectionResult(true);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectionResult projectGroup(WeComProjectedGroupMessage item) {
        if (item == null || item.sourceConversationId() == null || !bounded(item.msgid(), 256)
                || item.sendTime() < 0 || !("inbound".equals(item.direction())
                || "outbound".equals(item.direction()))) {
            throw new IllegalArgumentException("WeCom group projected message is invalid");
        }
        ChannelAccountEntity account = accountMapper.selectSingleActiveByChannelType("wecom");
        if (messages.findByProviderMessageId(account.getId(), item.msgid()).isPresent()) {
            return new ProjectionResult(false);
        }
        ConversationEntity conversation = conversations.getOrCreateSourceConversation(
                account.getId(), item.sourceConversationId());
        Instant occurredAt = Instant.ofEpochSecond(item.sendTime());
        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setChannelAccountId(account.getId());
        message.setConversationId(conversation.getId());
        message.setProviderMessageId(item.msgid());
        message.setDirection(item.direction());
        message.setMessageKind("text");
        message.setBodyText("");
        message.setOccurredAt(occurredAt);
        message.setCountsAsUnread("inbound".equals(item.direction()));
        message.setCurrentStatus("outbound".equals(item.direction()) ? "sent" : "delivered");
        message.setCurrentStatusAt(occurredAt);
        message.setMetadataJsonb("{\"wecomReference\":true,\"conversationType\":\"GROUP\"}");
        messages.insertWithSequence(message);
        return new ProjectionResult(true);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectionResult projectDirect(WeComProjectedDirectMessage item) {
        if (item == null || item.sourceConversationId() == null || !bounded(item.msgid(), 256)
                || item.contactParty() == null || item.installationId() == null || item.authCorpId() == null
                || item.authCorpId().isBlank() || item.sendTime() < 0
                || !("inbound".equals(item.direction()) || "outbound".equals(item.direction()))) {
            throw new IllegalArgumentException("WeCom direct projected message is invalid");
        }
        ChannelAccountEntity account = accountMapper.selectSingleActiveByChannelType("wecom");
        if (messages.findByProviderMessageId(account.getId(), item.msgid()).isPresent()) {
            ContactIdentityEntity identity = findOrCreateDirectIdentity(account, item, item.contactParty());
            bindDirectSourceConversation(item.sourceConversationId(), identity);
            return new ProjectionResult(false);
        }
        ContactIdentityEntity identity = findOrCreateDirectIdentity(account, item, item.contactParty());
        bindDirectSourceConversation(item.sourceConversationId(), identity);
        ConversationEntity conversation = conversations.getOrCreateConversation(account.getId(), identity.getId());
        Instant occurredAt = Instant.ofEpochSecond(item.sendTime());
        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setChannelAccountId(account.getId());
        message.setConversationId(conversation.getId());
        message.setProviderMessageId(item.msgid());
        message.setDirection(item.direction());
        message.setMessageKind("text");
        message.setBodyText("");
        message.setOccurredAt(occurredAt);
        message.setCountsAsUnread("inbound".equals(item.direction()));
        message.setCurrentStatus("outbound".equals(item.direction()) ? "sent" : "delivered");
        message.setCurrentStatusAt(occurredAt);
        message.setMetadataJsonb("{\"wecomReference\":true,\"conversationType\":\"DIRECT\"}");
        messages.insertWithSequence(message);
        if ("inbound".equals(item.direction()) && contactMemoryTriggerService != null) {
            contactMemoryTriggerService.markInboundPersisted(identity.getContactId(), message.getId(),
                    message.getIngestSequence(), occurredAt, message.getReceivedAt());
        }
        return new ProjectionResult(true);
    }

    private void bindDirectSourceConversation(UUID sourceConversationId, ContactIdentityEntity identity) {
        if (sourceConversations == null || sourceConversationId == null || identity == null
                || identity.getId() == null) return;
        sourceConversations.bindContactIdentity(sourceConversationId, identity.getId());
    }

    private ContactIdentityEntity findOrCreateDirectIdentity(ChannelAccountEntity account,
                                                               WeComProjectedDirectMessage item,
                                                               ContactParty party) {
        if (party == null || !bounded(party.partyType(), 32) || !bounded(party.providerPartyId(), 128)
                || !("EMPLOYEE".equals(party.partyType()) || "EXTERNAL_CONTACT".equals(party.partyType()))) {
            throw new IllegalArgumentException("WeCom direct contact party is invalid");
        }
        String displayName = partyDisplayName(item.installationId(), party);
        if (displayName.isBlank() && "EXTERNAL_CONTACT".equals(party.partyType())) {
            displayName = resolveDisplayName(item.authCorpId(), party.providerPartyId());
        }
        return findOrCreateIdentity(account, party.providerPartyId(), item.authCorpId(), displayName);
    }

    private String partyDisplayName(UUID installationId, ContactParty party) {
        if (parties == null || installationId == null) return "";
        WeComPartyEntity profile = parties.selectOne(new LambdaQueryWrapper<WeComPartyEntity>()
                .eq(WeComPartyEntity::getInstallationId, installationId)
                .eq(WeComPartyEntity::getPartyType, party.partyType())
                .eq(WeComPartyEntity::getProviderPartyId, party.providerPartyId()));
        return profile == null || profile.getDisplayName() == null ? "" : profile.getDisplayName().trim();
    }

    private ContactIdentityEntity findOrCreateIdentity(ChannelAccountEntity account, String externalUserId,
                                                       String authCorpId) {
        return findOrCreateIdentity(account, externalUserId, authCorpId, "");
    }

    private ContactIdentityEntity findOrCreateIdentity(ChannelAccountEntity account, String externalUserId,
                                                       String authCorpId, String displayNameOverride) {
        String scope = account.getId().toString();
        Optional<ContactIdentityEntity> existing = identities.findByNormalizedValueInScope(
                "wecom", scope, externalUserId);
        String displayName = displayNameOverride == null ? "" : displayNameOverride.trim();
        if (displayName.isBlank()) displayName = resolveDisplayName(authCorpId, externalUserId);
        if (existing.isPresent()) {
            ContactIdentityEntity identity = existing.get();
            if (!displayName.isBlank() && !displayName.equals(externalUserId)
                    && !displayName.equals(identity.getDisplayName())) {
                identities.updateDisplayName(identity.getId(), displayName);
                contacts.updateDisplayName(identity.getContactId(), displayName);
            }
            return identity;
        }
        ContactEntity contact = new ContactEntity();
        contact.setId(UUID.randomUUID());
        contact.setCreatedBy(account.getOwnerUserId());
        contact.setDisplayName(displayName.isBlank() ? externalUserId : displayName);
        contact.setStatus("active");
        contacts.insert(contact);
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contact.getId());
        identity.setChannelType("wecom");
        identity.setIdentityScope(scope);
        identity.setIdentityValue(externalUserId);
        identity.setNormalizedValue(externalUserId);
        identity.setDisplayName(displayName.isBlank() ? externalUserId : displayName);
        identity.setIsPrimary(true);
        identity.setVerifyStatus("unverified");
        identity.setSource("synced");
        identities.insert(identity);
        return identity;
    }

    private String resolveDisplayName(String authCorpId, String externalUserId) {
        if (externalContacts == null || authCorpId == null || authCorpId.isBlank()) return "";
        try {
            return externalContacts.displayNameForSync(authCorpId, externalUserId);
        } catch (RuntimeException ignored) {
            // Message projection remains available when the optional profile lookup is unavailable.
        }
        return "";
    }

    private static void requireValid(WeComProjectedMessage item) {
        if (item == null || !bounded(item.msgid(), 256) || !bounded(item.externalUserId(), 128)
                || !bounded(item.wecomUserId(), 128) || item.sendTime() < 0
                || !("inbound".equals(item.direction()) || "outbound".equals(item.direction()))) {
            throw new IllegalArgumentException("WeCom projected message is invalid");
        }
    }

    private static boolean bounded(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }

    public record WeComProjectedMessage(String msgid, String externalUserId, String wecomUserId,
                                        long sendTime, String direction, String authCorpId) {
        public WeComProjectedMessage(String msgid, String externalUserId, String wecomUserId,
                                     long sendTime, String direction) {
            this(msgid, externalUserId, wecomUserId, sendTime, direction, "");
        }
    }
    public record WeComProjectedGroupMessage(String msgid, UUID sourceConversationId,
                                             long sendTime, String direction) {}
    public record WeComProjectedDirectMessage(String msgid, UUID sourceConversationId,
                                              UUID installationId, String authCorpId,
                                              ContactParty contactParty, long sendTime, String direction) {}
    public record ContactParty(String partyType, String providerPartyId) {}
    public record ProjectionResult(boolean inserted) {}
}
