package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
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
    private final ChannelAccountMapper channelAccounts;
    private final ContactIdentityMapper identities;
    private final ContactMapper contacts;
    private final ConversationMapper conversations;
    private final MessageMapper messages;

    public WeComMessageProjector(ChannelAccountMapper channelAccounts,
                                 ContactIdentityMapper identities,
                                 ContactMapper contacts,
                                 ConversationMapper conversations,
                                 MessageMapper messages) {
        this.channelAccounts = channelAccounts;
        this.identities = identities;
        this.contacts = contacts;
        this.conversations = conversations;
        this.messages = messages;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectionResult project(WeComProjectedMessage item) {
        requireValid(item);
        ChannelAccountEntity account = channelAccounts.selectSingleActiveByChannelType("wecom");
        if (messages.findByProviderMessageId(account.getId(), item.msgid()).isPresent()) {
            return new ProjectionResult(false);
        }
        ContactIdentityEntity identity = findOrCreateIdentity(account, item.externalUserId());
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
        return new ProjectionResult(true);
    }

    private ContactIdentityEntity findOrCreateIdentity(ChannelAccountEntity account, String externalUserId) {
        String scope = account.getId().toString();
        Optional<ContactIdentityEntity> existing = identities.findByNormalizedValueInScope(
                "wecom", scope, externalUserId);
        if (existing.isPresent()) return existing.get();
        ContactEntity contact = new ContactEntity();
        contact.setId(UUID.randomUUID());
        contact.setDisplayName(externalUserId);
        contact.setStatus("active");
        contacts.insert(contact);
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contact.getId());
        identity.setChannelType("wecom");
        identity.setIdentityScope(scope);
        identity.setIdentityValue(externalUserId);
        identity.setNormalizedValue(externalUserId);
        identity.setDisplayName(externalUserId);
        identity.setIsPrimary(true);
        identity.setVerifyStatus("unverified");
        identity.setSource("synced");
        identities.insert(identity);
        return identity;
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
                                        long sendTime, String direction) {}
    public record ProjectionResult(boolean inserted) {}
}
