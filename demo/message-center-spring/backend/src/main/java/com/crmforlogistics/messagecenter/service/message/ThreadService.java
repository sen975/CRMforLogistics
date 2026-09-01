package com.crmforlogistics.messagecenter.service.message;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.crmforlogistics.messagecenter.dto.response.MessageResponse;
import com.crmforlogistics.messagecenter.dto.response.MessageAttachmentResponse;
import com.crmforlogistics.messagecenter.dto.response.ThreadResponse;
import com.crmforlogistics.messagecenter.dto.response.WeComGroupThreadResponse;
import com.crmforlogistics.messagecenter.dto.response.WeComPartyView;
import com.crmforlogistics.messagecenter.dto.response.WeComThreadResponse;
import com.crmforlogistics.messagecenter.dto.response.RelatedWeComGroupResponse;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.AttachmentMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceConversationMapper;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceParticipantMapper;
import com.crmforlogistics.messagecenter.mapper.WeComPartyMapper;
import com.crmforlogistics.messagecenter.entity.WeComSourceConversationEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final WeComSourceConversationMapper weComSourceConversationMapper;
    private final WeComChatDataMessageMapper weComChatDataMessageMapper;
    private final WeComSourceParticipantMapper weComSourceParticipantMapper;
    private final WeComPartyMapper weComPartyMapper;

    @Autowired
    public ThreadService(ConversationMapper conversationMapper,
                         MessageMapper messageMapper,
                         ContactIdentityMapper contactIdentityMapper,
                         ChannelAccountMapper channelAccountMapper,
                         TemplateMessageTextResolver templateMessageTextResolver,
                         AttachmentMapper attachmentMapper,
                         WeComSourceConversationMapper weComSourceConversationMapper,
                         WeComChatDataMessageMapper weComChatDataMessageMapper,
                         WeComSourceParticipantMapper weComSourceParticipantMapper,
                         WeComPartyMapper weComPartyMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.contactIdentityMapper = contactIdentityMapper;
        this.channelAccountMapper = channelAccountMapper;
        this.templateMessageTextResolver = templateMessageTextResolver;
        this.attachmentMapper = attachmentMapper;
        this.weComSourceConversationMapper = weComSourceConversationMapper;
        this.weComChatDataMessageMapper = weComChatDataMessageMapper;
        this.weComSourceParticipantMapper = weComSourceParticipantMapper;
        this.weComPartyMapper = weComPartyMapper;
    }

    public ThreadService(ConversationMapper conversationMapper,
                         MessageMapper messageMapper,
                         ContactIdentityMapper contactIdentityMapper,
                         ChannelAccountMapper channelAccountMapper,
                         TemplateMessageTextResolver templateMessageTextResolver,
                         AttachmentMapper attachmentMapper) {
        this(conversationMapper, messageMapper, contactIdentityMapper, channelAccountMapper,
                templateMessageTextResolver, attachmentMapper, null, null);
    }

    public ThreadService(ConversationMapper conversationMapper,
                         MessageMapper messageMapper,
                         ContactIdentityMapper contactIdentityMapper,
                         ChannelAccountMapper channelAccountMapper,
                         TemplateMessageTextResolver templateMessageTextResolver,
                         AttachmentMapper attachmentMapper,
                         WeComSourceConversationMapper sourceConversations,
                         WeComChatDataMessageMapper chatDataMessages) {
        this(conversationMapper, messageMapper, contactIdentityMapper, channelAccountMapper,
                templateMessageTextResolver, attachmentMapper, sourceConversations, chatDataMessages,
                null, null);
    }

    public WeComThreadResponse getContactWeComThread(UUID userId, UUID contactId, String cursor, int limit) {
        ThreadResponse response = threadPage(userId, contactId, "wecom", cursor, limit);
        // The mapper already restricts source conversations to DIRECT. Keep a final
        // response guard so legacy rows cannot leak GROUP messages into a contact view.
        List<MessageResponse> directItems = response.items().stream()
                .filter(item -> !"GROUP".equalsIgnoreCase(item.conversationType()))
                .toList();
        List<UUID> sourceIds = directItems.stream()
                .map(MessageResponse::sourceConversationId).filter(java.util.Objects::nonNull).distinct().toList();
        List<RelatedWeComGroupResponse> relatedGroups = conversationMapper.listAccessibleWeComGroupsForContact(contactId, userId)
                .stream()
                .map(group -> new RelatedWeComGroupResponse(group.sourceConversationId(), group.displayName(),
                        group.avatarUrl(), group.participantCount()))
                .toList();
        return new WeComThreadResponse(contactId, sourceIds, relatedGroups,
                directItems, response.nextCursor(), directItems.size(), response.threadRevision());
    }

    public WeComGroupThreadResponse getWeComGroupThread(UUID userId, UUID sourceConversationId,
                                                         String cursor, int limit) {
        if (weComSourceParticipantMapper == null || weComChatDataMessageMapper == null) {
            throw new IllegalStateException("WECOM_THREAD_PROJECTION_UNAVAILABLE");
        }
        ConversationMapper.WeComSourceConversationAccessRow source =
                conversationMapper.findAccessibleWeComGroup(userId, sourceConversationId);
        if (source == null) throw new IllegalArgumentException("WECOM_GROUP_NOT_ACCESSIBLE");
        Page<MessageEntity> page = new Page<>(1, clampLimit(limit) + 1, false);
        IPage<MessageEntity> result = messageMapper.listMessagesByConversations(
                page, List.of(source.conversationId()), userId, decodeCursor(cursor).beforeCursor,
                decodeCursor(cursor).beforeId, true);
        List<MessageEntity> fetched = result.getRecords();
        boolean hasMore = fetched.size() > clampLimit(limit);
        List<MessageEntity> items = new ArrayList<>(fetched.subList(0, Math.min(clampLimit(limit), fetched.size())));
        java.util.Collections.reverse(items);
        ChannelAccountEntity account = channelAccountMapper.selectOne(
                new LambdaQueryWrapper<ChannelAccountEntity>().eq(ChannelAccountEntity::getChannelType, "wecom")
                        .eq(ChannelAccountEntity::getAuthStatus, "active").isNull(ChannelAccountEntity::getDeletedAt));
        List<MessageResponse> responses = items.stream()
                .map(item -> toSourceMessageResponse(item, account, userId, attachmentMapper.listReadyByMessageId(item.getId()).stream()
                        .map(MessageAttachmentResponse::from).toList()))
                .toList();
        String next = hasMore && !items.isEmpty()
                ? encodeCursor(items.get(0).getOccurredAt(), items.get(0).getId()) : null;
        List<WeComPartyView> participants = weComSourceParticipantMapper.listPartyViews(sourceConversationId, userId);
        return new WeComGroupThreadResponse(sourceConversationId, source.providerConversationKey(),
                source.displayName(), source.avatarUrl(), null, participants, responses, next,
                responses.size(), "");
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
            ChannelAccountEntity ca = findChannelAccount(ci);
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
        if ("wecom".equalsIgnoreCase(channelType) || channelType == null || channelType.isBlank()) {
            for (ConversationEntity source : conversationMapper.listAccessibleWeComSourceForContact(contactId, userId)) {
                if (!conversationIds.contains(source.getId())) conversationIds.add(source.getId());
            }
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

        // 6. Fetch ready attachments once for the bounded message page.
        Map<UUID, List<MessageAttachmentResponse>> attachmentsByMessageId = new LinkedHashMap<>();
        if (!items.isEmpty()) {
            List<UUID> messageIds = items.stream().map(MessageEntity::getId).toList();
            attachmentMapper.listReadyByMessageIds(messageIds).forEach(attachment ->
                    attachmentsByMessageId
                            .computeIfAbsent(attachment.getMessageId(), ignored -> new ArrayList<>())
                            .add(MessageAttachmentResponse.from(attachment)));
        }

        // 7. Build MessageResponse records
        List<MessageResponse> messageResponses = new ArrayList<>();
        for (MessageEntity entity : items) {
            IdentityChannelPair pair = identityByConv.get(entity.getConversationId());
            if (pair != null) {
                messageResponses.add(toMessageResponse(entity, pair.channelAccount(), pair.identity(), userId,
                        attachmentsByMessageId.getOrDefault(entity.getId(), List.of())));
            } else {
                ChannelAccountEntity account = channelAccountMapper.selectById(entity.getChannelAccountId());
                if (account != null && "wecom".equalsIgnoreCase(account.getChannelType())) {
                    messageResponses.add(toSourceMessageResponse(entity, account, userId,
                            attachmentsByMessageId.getOrDefault(entity.getId(), List.of())));
                }
            }
        }

        // 8. Encode next cursor from the oldest item in this page (first after reverse)
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            MessageEntity oldestItem = items.get(0);
            nextCursor = encodeCursor(oldestItem.getOccurredAt(), oldestItem.getId());
        }

        // 9. Return ThreadResponse
        return new ThreadResponse(messageResponses, nextCursor, messageResponses.size(), "");
    }

    private ChannelAccountEntity findChannelAccount(ContactIdentityEntity identity) {
        String scope = identity.getIdentityScope();
        if (scope != null && !scope.isBlank()) {
            try {
                UUID accountId = UUID.fromString(scope);
                ChannelAccountEntity scoped = channelAccountMapper.selectById(accountId);
                if (scoped != null
                        && identity.getChannelType().equalsIgnoreCase(scoped.getChannelType())
                        && "active".equalsIgnoreCase(scoped.getAuthStatus())
                        && scoped.getDeletedAt() == null) {
                    return scoped;
                }
            } catch (IllegalArgumentException ignored) {
                // Email identities use a symbolic scope; only UUID scopes identify accounts.
            }
        }
        return channelAccountMapper.selectOne(
                new LambdaQueryWrapper<ChannelAccountEntity>()
                        .eq(ChannelAccountEntity::getChannelType, identity.getChannelType())
                        .eq(ChannelAccountEntity::getAuthStatus, "active")
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
                                              ContactIdentityEntity identity,
                                              UUID userId,
                                              List<MessageAttachmentResponse> attachments) {
        String channelType = channelAccount.getChannelType();
        String direction = directionForViewer(entity, channelType, userId);
        boolean isInbound = "inbound".equals(direction);
        String from = isInbound ? identityValue(identity) : channelAccountName(channelAccount);
        String to = isInbound ? channelAccountName(channelAccount) : identityValue(identity);

        return new MessageResponse(
                entity.getId(),
                entity.getProviderMessageId(),
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
                attachments,
                null,
                null,
                null,
                "wecom".equalsIgnoreCase(channelType) && weComChatDataMessageMapper != null
                        ? weComChatDataMessageMapper.findMessageSenderView(entity.getProviderMessageId(), userId)
                        : null
        );
    }

    private MessageResponse toSourceMessageResponse(MessageEntity entity,
                                                     ChannelAccountEntity account,
                                                     UUID userId,
                                                     List<MessageAttachmentResponse> attachments) {
        String display = channelAccountName(account);
        UUID sourceConversationId = null;
        String conversationType = null;
        String conversationDisplayName = null;
        if (weComSourceConversationMapper != null && entity.getConversationId() != null) {
            ConversationEntity conversation = conversationMapper.selectById(entity.getConversationId());
            if (conversation != null && conversation.getSourceConversationId() != null) {
                sourceConversationId = conversation.getSourceConversationId();
                WeComSourceConversationEntity source = weComSourceConversationMapper.selectById(sourceConversationId);
                if (source != null) {
                    conversationType = source.getConversationType();
                    conversationDisplayName = source.getDisplayName();
                }
            }
        }
        String direction = directionForViewer(entity, account.getChannelType(), userId);
        WeComPartyView sender = weComChatDataMessageMapper == null ? null
                : weComChatDataMessageMapper.findMessageSenderView(entity.getProviderMessageId(), userId);
        return new MessageResponse(entity.getId(), entity.getProviderMessageId(), direction,
                entity.getMessageKind(), entity.getSubject(), templateMessageTextResolver.resolve(entity),
                entity.getBodyHtml(), account.getChannelType(), display, display, entity.getOccurredAt(),
                entity.getCurrentStatus(), entity.getIngestSequence() != null
                ? entity.getIngestSequence().intValue() : 0, attachments,
                sourceConversationId, conversationType, conversationDisplayName, sender);
    }

    private String directionForViewer(MessageEntity entity, String channelType, UUID userId) {
        if (!"wecom".equalsIgnoreCase(channelType) || weComChatDataMessageMapper == null
                || entity.getProviderMessageId() == null || userId == null) {
            return entity.getDirection();
        }
        String resolved = weComChatDataMessageMapper.resolveDirectionForViewer(
                entity.getProviderMessageId(), userId);
        return "inbound".equals(resolved) || "outbound".equals(resolved)
                ? resolved : entity.getDirection();
    }

    private static String identityValue(ContactIdentityEntity identity) {
        String display = identity.getDisplayName();
        return display != null && !display.isBlank() ? display : identity.getIdentityValue();
    }

    private static String channelAccountName(ChannelAccountEntity channelAccount) {
        if (channelAccount == null) return "企业微信";
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
