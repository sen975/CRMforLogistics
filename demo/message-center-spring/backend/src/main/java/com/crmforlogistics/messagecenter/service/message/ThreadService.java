package com.crmforlogistics.messagecenter.service.message;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.crmforlogistics.messagecenter.dto.response.MessageResponse;
import com.crmforlogistics.messagecenter.dto.response.MessageAttachmentResponse;
import com.crmforlogistics.messagecenter.dto.response.ThreadResponse;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.AttachmentMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class ThreadService {

    private static final int DEFAULT_THREAD_LIMIT = 10;
    private static final int MAX_THREAD_LIMIT = 50;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ContactIdentityMapper contactIdentityMapper;
    private final ChannelAccountMapper channelAccountMapper;
    private final TemplateMessageTextResolver templateMessageTextResolver;
    private final AttachmentMapper attachmentMapper;

    public ThreadService(ConversationMapper conversationMapper,
                         MessageMapper messageMapper,
                         ContactIdentityMapper contactIdentityMapper,
                         ChannelAccountMapper channelAccountMapper,
                         TemplateMessageTextResolver templateMessageTextResolver,
                         AttachmentMapper attachmentMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.contactIdentityMapper = contactIdentityMapper;
        this.channelAccountMapper = channelAccountMapper;
        this.templateMessageTextResolver = templateMessageTextResolver;
        this.attachmentMapper = attachmentMapper;
    }

    public ThreadResponse threadPage(UUID userId, UUID contactId, String channelType,
                                     String cursor, int limit) {
        int safeLimit = clampLimit(limit);

        // 1. Resolve contact identities
        List<ContactIdentityEntity> identities = contactIdentityMapper.findByContactId(contactId);
        if (channelType != null && !channelType.isBlank()) {
            identities = identities.stream()
                    .filter(i -> channelType.equals(i.getChannelType()))
                    .toList();
        }
        if (identities.isEmpty()) {
            return new ThreadResponse(List.of(), null, 0, "");
        }

        // 2. Find all matching identity / channel-account pairs
        record IdentityChannelPair(ContactIdentityEntity identity, ChannelAccountEntity channelAccount) {}
        List<IdentityChannelPair> pairs = new ArrayList<>();
        for (ContactIdentityEntity ci : identities) {
            ChannelAccountEntity ca = findChannelAccount(ci.getChannelType());
            if (ca != null) {
                pairs.add(new IdentityChannelPair(ci, ca));
            }
        }
        if (pairs.isEmpty()) {
            return new ThreadResponse(List.of(), null, 0, "");
        }

        // 3. Get or create conversations for all matching pairs
        List<UUID> conversationIds = new ArrayList<>();
        java.util.Map<UUID, IdentityChannelPair> identityByConv = new java.util.LinkedHashMap<>();
        for (IdentityChannelPair pair : pairs) {
            ConversationEntity conversation = conversationMapper.getOrCreateConversation(
                    pair.channelAccount().getId(), pair.identity().getId());
            conversationIds.add(conversation.getId());
            identityByConv.put(conversation.getId(), pair);
        }

        // 4. Decode cursor: base64 "{occurredAt}:{messageId}"
        CursorPair decoded = decodeCursor(cursor);

        // 5. Query messages across all conversations (fetch one extra to detect hasMore)
        Page<MessageEntity> page = new Page<>(1, safeLimit + 1, false);
        IPage<MessageEntity> result = messageMapper.listMessagesByConversations(
                page, conversationIds, userId, decoded.beforeCursor, decoded.beforeId,
                isCurrentUserAdmin());

        List<MessageEntity> fetched = result.getRecords();
        boolean hasMore = fetched.size() > safeLimit;
        List<MessageEntity> items = new ArrayList<>(
                fetched.subList(0, Math.min(safeLimit, fetched.size())));
        // SQL returns DESC (newest first); reverse to chronological (oldest first)
        java.util.Collections.reverse(items);

        // 6. Build MessageResponse records

        List<MessageResponse> messageResponses = new ArrayList<>();
        for (MessageEntity entity : items) {
            IdentityChannelPair pair = identityByConv.get(entity.getConversationId());
            if (pair != null) {
                messageResponses.add(toMessageResponse(entity, pair.channelAccount(), pair.identity()));
            }
        }

        // 7. Encode next cursor from the oldest item in this page (first after reverse)
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            MessageEntity oldestItem = items.get(0);
            nextCursor = encodeCursor(oldestItem.getOccurredAt(), oldestItem.getId());
        }

        // 8. Return ThreadResponse
        return new ThreadResponse(messageResponses, nextCursor, messageResponses.size(), "");
    }

    private ChannelAccountEntity findChannelAccount(String channelType) {
        return channelAccountMapper.selectOne(
                new LambdaQueryWrapper<ChannelAccountEntity>()
                        .eq(ChannelAccountEntity::getChannelType, channelType)
                        .isNull(ChannelAccountEntity::getDeletedAt));
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_THREAD_LIMIT;
        }
        return Math.max(1, Math.min(MAX_THREAD_LIMIT, limit));
    }

    private MessageResponse toMessageResponse(MessageEntity entity,
                                              ChannelAccountEntity channelAccount,
                                              ContactIdentityEntity identity) {
        String channelType = channelAccount.getChannelType();
        String direction = entity.getDirection();
        boolean isInbound = "inbound".equals(direction);
        String from = isInbound ? identityValue(identity) : channelAccountName(channelAccount);
        String to = isInbound ? channelAccountName(channelAccount) : identityValue(identity);

        return new MessageResponse(
                entity.getId(),
                direction,
                entity.getMessageKind(),
                entity.getSubject(),
                templateMessageTextResolver.resolve(entity),
                entity.getBodyHtml(),
                channelType,
                from,
                to,
                entity.getOccurredAt(),
                entity.getCurrentStatus(),
                entity.getIngestSequence() != null ? entity.getIngestSequence().intValue() : 0,
                attachmentMapper.listReadyByMessageId(entity.getId()).stream()
                        .map(MessageAttachmentResponse::from)
                        .toList()
        );
    }

    private static String identityValue(ContactIdentityEntity identity) {
        String display = identity.getDisplayName();
        return display != null && !display.isBlank() ? display : identity.getIdentityValue();
    }

    private static String channelAccountName(ChannelAccountEntity channelAccount) {
        String name = channelAccount.getName();
        return name != null && !name.isBlank() ? name : channelAccount.getAccountIdentifier();
    }

    // ---- cursor encoding / decoding ----

    private record CursorPair(Instant beforeCursor, UUID beforeId) {}

    private static CursorPair decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new CursorPair(null, null);
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            String raw = new String(decoded, StandardCharsets.UTF_8);
            int colon = raw.lastIndexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("invalid cursor format");
            }
            Instant timestamp = Instant.parse(raw.substring(0, colon));
            UUID id = UUID.fromString(raw.substring(colon + 1));
            return new CursorPair(timestamp, id);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid thread cursor", e);
        }
    }

    private static boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN"));
    }

    private static String encodeCursor(Instant occurredAt, UUID messageId) {
        if (occurredAt == null || messageId == null) {
            return null;
        }
        String raw = occurredAt.toString() + ":" + messageId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
