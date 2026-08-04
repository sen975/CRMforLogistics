package com.crmforlogistics.messagecenter.service.contact;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.crmforlogistics.messagecenter.dto.response.ContactResponse;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Contact query service with user-scoped listing and detail.
 *
 * <p>Ported from {@code JdbcContactRepository.listForUser()} and
 * {@code loadProjection()} in the old project. Authorization is enforced
 * by {@link ContactMapper#listForUser} -- the SQL already scopes results to
 * contacts the caller owns, is assigned to, or has been granted access.
 */
@Service
public class ContactService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ContactMapper contactMapper;
    private final ContactIdentityMapper contactIdentityMapper;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    public ContactService(ContactMapper contactMapper,
                          ContactIdentityMapper contactIdentityMapper,
                          ConversationMapper conversationMapper,
                          MessageMapper messageMapper) {
        this.contactMapper = contactMapper;
        this.contactIdentityMapper = contactIdentityMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    /**
     * List contacts visible to a user with cursor-based pagination.
     *
     * <p>Authorization is enforced by the mapper SQL (ownership, assignment,
     * or active access grant). Each contact is enriched with channel types
     * from its identities, the last message timestamp, and an unread count.
     *
     * @param userId               the current user's UUID
     * @param search               optional search string (ilike on display_name + remark)
     * @param beforeLastMessageAt  cursor: sort_at timestamp
     * @param beforeId             cursor: contact id
     * @param page                 page number (1-based)
     * @param size                 page size (1-100)
     * @return paginated contact responses
     */
    public IPage<ContactResponse> listForUser(UUID userId, String search,
                                               Instant beforeLastMessageAt, UUID beforeId,
                                               int page, int size) {
        int safeSize = clampSize(size);
        Page<ContactEntity> pageParam = new Page<>(page, safeSize);
        IPage<ContactEntity> pageResult = contactMapper.listForUser(
                pageParam, userId, search, beforeLastMessageAt, beforeId);

        List<ContactResponse> records = pageResult.getRecords().stream()
                .map(this::toResponse)
                .toList();

        Page<ContactResponse> resultPage = new Page<>(page, safeSize);
        resultPage.setRecords(records);
        resultPage.setTotal(pageResult.getTotal());
        return resultPage;
    }

    /**
     * Get a single contact by id, enriched with channel types, last message
     * info, and unread count.
     *
     * @param userId    the current user's UUID (for future authorization checks)
     * @param contactId the contact UUID
     * @return the contact response
     * @throws IllegalArgumentException if contact not found
     */
    public ContactResponse getById(UUID userId, UUID contactId) {
        ContactEntity entity = contactMapper.findById(contactId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Contact not found: " + contactId));
        return toResponse(entity);
    }

    private ContactResponse toResponse(ContactEntity entity) {
        UUID contactId = entity.getId();
        List<ContactIdentityEntity> identities =
                contactIdentityMapper.findByContactId(contactId);

        List<String> channelTypes = identities.stream()
                .map(ContactIdentityEntity::getChannelType)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        List<UUID> identityIds = identities.stream()
                .map(ContactIdentityEntity::getId)
                .toList();

        Instant lastMessageAt = null;
        String lastText = null;
        int messageCount = 0;
        int unreadCount = 0;

        if (!identityIds.isEmpty()) {
            LambdaQueryWrapper<ConversationEntity> convWrapper =
                    new LambdaQueryWrapper<>();
            convWrapper.in(ConversationEntity::getContactIdentityId, identityIds);
            List<ConversationEntity> conversations =
                    conversationMapper.selectList(convWrapper);

            lastMessageAt = conversations.stream()
                    .map(ConversationEntity::getLastMessageAt)
                    .filter(Objects::nonNull)
                    .max(Instant::compareTo)
                    .orElse(null);

            List<UUID> conversationIds = conversations.stream()
                    .map(ConversationEntity::getId)
                    .toList();

            if (!conversationIds.isEmpty()) {
                // Most recent message body text
                LambdaQueryWrapper<MessageEntity> lastMsgWrapper =
                        new LambdaQueryWrapper<>();
                lastMsgWrapper.in(MessageEntity::getConversationId, conversationIds);
                lastMsgWrapper.orderByDesc(MessageEntity::getOccurredAt);
                lastMsgWrapper.last("LIMIT 1");
                MessageEntity lastMsg = messageMapper.selectOne(lastMsgWrapper);
                if (lastMsg != null) {
                    lastText = lastMsg.getBodyText();
                }

                // Total message count across all the contact's conversations
                LambdaQueryWrapper<MessageEntity> countWrapper =
                        new LambdaQueryWrapper<>();
                countWrapper.in(MessageEntity::getConversationId, conversationIds);
                messageCount = messageMapper.selectCount(countWrapper).intValue();

                // Unread count: messages with counts_as_unread = true
                LambdaQueryWrapper<MessageEntity> unreadWrapper =
                        new LambdaQueryWrapper<>();
                unreadWrapper.in(MessageEntity::getConversationId, conversationIds);
                unreadWrapper.eq(MessageEntity::getCountsAsUnread, true);
                unreadCount = messageMapper.selectCount(unreadWrapper).intValue();
            }
        }

        return new ContactResponse(
                contactId,
                entity.getDisplayName(),
                entity.getRemark(),
                channelTypes,
                lastMessageAt,
                lastText,
                messageCount,
                unreadCount);
    }

    private static int clampSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
