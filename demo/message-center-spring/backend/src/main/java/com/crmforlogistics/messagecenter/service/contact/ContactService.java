package com.crmforlogistics.messagecenter.service.contact;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.crmforlogistics.messagecenter.dto.request.SearchMode;
import com.crmforlogistics.messagecenter.dto.response.ContactIdentityResponse;
import com.crmforlogistics.messagecenter.dto.response.ContactMemoryResponse;
import com.crmforlogistics.messagecenter.dto.response.ContactResponse;
import com.crmforlogistics.messagecenter.dto.response.ContactTagResponse;
import com.crmforlogistics.messagecenter.dto.response.PhoneContactBindingResponse;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppAccountResolver;
import com.crmforlogistics.messagecenter.service.contactmemory.ContactMemoryQueryService;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private static final int MAX_ASSISTANT_CHANNEL_TYPES = 8;
    private static final int MAX_ASSISTANT_CHANNEL_PROFILES = 8;
    private static final int ASSISTANT_VALUE_MAX_CHARS = 255;
    private static final int ASSISTANT_LABEL_MAX_CHARS = 80;

    private final ContactMapper contactMapper;
    private final ContactIdentityMapper contactIdentityMapper;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ChatAppAccountResolver chatAppAccountResolver;
    private final ContactTagMapper contactTagMapper;
    private final ContactMemoryQueryService contactMemoryQueryService;
    private final ContactTagMatchResolver contactTagMatchResolver;
    private final ChannelAccountMapper channelAccountMapper;

    public ContactService(ContactMapper contactMapper,
                          ContactIdentityMapper contactIdentityMapper,
                          ConversationMapper conversationMapper,
                          MessageMapper messageMapper,
                          ChatAppAccountResolver chatAppAccountResolver) {
        this(contactMapper, contactIdentityMapper, conversationMapper, messageMapper,
                chatAppAccountResolver, null, null, null);
    }

    public ContactService(ContactMapper contactMapper,
                          ContactIdentityMapper contactIdentityMapper,
                          ConversationMapper conversationMapper,
                          MessageMapper messageMapper,
                          ChatAppAccountResolver chatAppAccountResolver,
                          ContactTagMapper contactTagMapper,
                          ContactMemoryQueryService contactMemoryQueryService,
                          ContactTagMatchResolver contactTagMatchResolver) {
        this(contactMapper, contactIdentityMapper, conversationMapper, messageMapper, chatAppAccountResolver,
                contactTagMapper, contactMemoryQueryService, contactTagMatchResolver, null);
    }

    @Autowired
    public ContactService(ContactMapper contactMapper,
                          ContactIdentityMapper contactIdentityMapper,
                          ConversationMapper conversationMapper,
                          MessageMapper messageMapper,
                          ChatAppAccountResolver chatAppAccountResolver,
                          ContactTagMapper contactTagMapper,
                          ContactMemoryQueryService contactMemoryQueryService,
                          ContactTagMatchResolver contactTagMatchResolver,
                          ChannelAccountMapper channelAccountMapper) {
        this.contactMapper = contactMapper;
        this.contactIdentityMapper = contactIdentityMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.chatAppAccountResolver = chatAppAccountResolver;
        this.contactTagMapper = contactTagMapper;
        this.contactMemoryQueryService = contactMemoryQueryService;
        this.contactTagMatchResolver = contactTagMatchResolver;
        this.channelAccountMapper = channelAccountMapper;
    }

    public ContactService(ContactMapper contactMapper,
                          ContactIdentityMapper contactIdentityMapper,
                          ConversationMapper conversationMapper,
                          MessageMapper messageMapper,
                          ChatAppAccountResolver chatAppAccountResolver,
                          ContactTagMapper contactTagMapper) {
        this(contactMapper, contactIdentityMapper, conversationMapper, messageMapper,
                chatAppAccountResolver, contactTagMapper, null, null);
    }

    public ContactService(ContactMapper contactMapper,
                          ContactIdentityMapper contactIdentityMapper,
                          ConversationMapper conversationMapper,
                          MessageMapper messageMapper,
                          ChatAppAccountResolver chatAppAccountResolver,
                          ChannelAccountMapper channelAccountMapper) {
        this(contactMapper, contactIdentityMapper, conversationMapper, messageMapper, chatAppAccountResolver,
                null, null, null, channelAccountMapper);
    }

    /** Only channel names from identities currently visible to the caller. */
    public List<String> listAuthorizedChannelTypes(UUID userId, UUID contactId) {
        if (userId == null || contactId == null) {
            throw new IllegalArgumentException("Contact not found");
        }
        return contactIdentityMapper.findByContactIdAndOwner(contactId, userId).stream()
                .map(ContactIdentityEntity::getChannelType)
                .filter(type -> type != null && type.matches("[a-z][a-z0-9_-]{0,31}"))
                .distinct()
                .sorted()
                .limit(MAX_ASSISTANT_CHANNEL_TYPES)
                .toList();
    }

    /**
     * Explicit assistant projection of caller-authorized channel identities.
     * Internal scope IDs, normalized values, database IDs and credentials never leave this owner.
     */
    public List<AuthorizedChannelProfile> listAuthorizedChannelProfiles(UUID userId, UUID contactId) {
        if (userId == null || contactId == null) {
            throw new IllegalArgumentException("Contact not found");
        }
        return contactIdentityMapper.findByContactIdAndOwner(contactId, userId).stream()
                .filter(identity -> identity != null
                        && identity.getChannelType() != null
                        && identity.getChannelType().matches("[a-z][a-z0-9_-]{0,31}"))
                .filter(identity -> identity.getIdentityValue() != null && !identity.getIdentityValue().isBlank())
                .limit(MAX_ASSISTANT_CHANNEL_PROFILES)
                .map(identity -> new AuthorizedChannelProfile(
                        identity.getChannelType(),
                        truncate(identity.getIdentityValue(), ASSISTANT_VALUE_MAX_CHARS),
                        truncate(blankToNull(identity.getDisplayName()), ASSISTANT_LABEL_MAX_CHARS),
                        accountLabel(identity, userId)))
                .toList();
    }

    private String accountLabel(ContactIdentityEntity identity, UUID userId) {
        if (channelAccountMapper == null || identity == null || userId == null) {
            return null;
        }
        UUID accountId;
        try {
            accountId = UUID.fromString(identity.getIdentityScope());
        } catch (RuntimeException ignored) {
            return null;
        }
        ChannelAccountEntity account = channelAccountMapper.findByIdAndOwner(accountId, userId);
        if (account == null || account.getDeletedAt() != null) {
            return null;
        }
        return truncate(firstNonBlank(account.getName(), account.getRemark()), ASSISTANT_LABEL_MAX_CHARS);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String truncate(String value, int max) {
        return value == null ? null : value.length() <= max ? value : value.substring(0, max);
    }

    public record AuthorizedChannelProfile(String channelType, String identityValue,
                                           String displayName, String accountLabel) {
    }

    /**
     * List contacts visible to a user with cursor-based pagination.
     *
     * <p>Authorization is enforced by the mapper SQL (ownership, assignment,
     * or active access grant). Each contact is enriched with channel types
     * from its identities, the last message timestamp, and an unread count.
     *
     * @param userId               the current user's UUID
     * @param search               optional search string; contact mode matches
     *                             display_name + remark (ilike), tag mode matches
     *                             the caller's active tag names (ilike)
     * @param beforeLastMessageAt  cursor: sort_at timestamp
     * @param beforeId             cursor: contact id
     * @param page                 page number (1-based)
     * @param size                 page size (1-100)
     * @return paginated contact responses
     */
    public IPage<ContactResponse> listForUser(UUID userId, String search,
                                               Instant beforeLastMessageAt, UUID beforeId,
                                               int page, int size) {
        return listForUser(userId, search, SearchMode.CONTACT, beforeLastMessageAt, beforeId,
                page, size, null, null);
    }

    public IPage<ContactResponse> listForUser(UUID userId, String search,
                                               Instant beforeLastMessageAt, UUID beforeId,
                                               int page, int size, String channelType,
                                               UUID channelAccountId) {
        return listForUser(userId, search, SearchMode.CONTACT, beforeLastMessageAt, beforeId,
                page, size, channelType, channelAccountId);
    }

    public IPage<ContactResponse> listForUser(UUID userId, String search, SearchMode searchMode,
                                               Instant beforeLastMessageAt, UUID beforeId,
                                               int page, int size, String channelType,
                                               UUID channelAccountId) {
        boolean chatAppFilter = "chatapp".equalsIgnoreCase(channelType);
        if ((chatAppFilter && channelAccountId == null)
                || (channelAccountId != null && !chatAppFilter)) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
        }
        if (chatAppFilter) {
            chatAppAccountResolver.requireOwnedAccount(userId, channelAccountId);
        }
        int safeSize = clampSize(size);
        Page<ContactEntity> pageParam = new Page<>(page, safeSize);
        boolean isAdmin = isCurrentUserAdmin();
        IPage<ContactEntity> pageResult = contactMapper.listForUser(
                pageParam, userId, search, searchMode.isTag(), beforeLastMessageAt, beforeId, isAdmin,
                chatAppFilter ? "chatapp" : null, chatAppFilter ? channelAccountId : null);
        // The list query only applies LIMIT; nothing populated the total, so this endpoint
        // reported "0 contacts" on the home page. Counted with the same filter fragment the
        // list uses, and deliberately without the cursor: the total describes the filter,
        // not the remainder of a scroll.
        long total = contactMapper.countForUser(userId, search, searchMode.isTag(), isAdmin,
                chatAppFilter ? "chatapp" : null, chatAppFilter ? channelAccountId : null);

        List<UUID> contactIds = pageResult.getRecords().stream()
                .map(ContactEntity::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<UUID, List<String>> matched = contactTagMatchResolver == null
                ? Map.of()
                : contactTagMatchResolver.matchNamesByContact(userId, contactIds, search, searchMode);
        List<ContactResponse> records = pageResult.getRecords().stream()
                .map(contact -> toResponse(contact, userId)
                        .withMatchedTags(matched.getOrDefault(contact.getId(), List.of())))
                .toList();

        Page<ContactResponse> resultPage = new Page<>(page, safeSize);
        resultPage.setRecords(records);
        resultPage.setTotal(total);
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
        ContactEntity entity = contactMapper.findAccessibleById(contactId, userId, isCurrentUserAdmin())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Contact not found: " + contactId));
        return toResponse(entity, userId);
    }

    private ContactResponse toResponse(ContactEntity entity, UUID userId) {
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
            List<ConversationEntity> conversations =
                    conversationMapper.listAccessibleForContact(contactId, userId);

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

        List<ContactIdentityResponse> identityResponses = identities.stream()
                .map(i -> new ContactIdentityResponse(
                        i.getId(),
                        i.getChannelType(),
                        i.getIdentityScope(),
                        i.getIdentityValue(),
                        i.getDisplayName()))
                .toList();

        List<ContactTagResponse> tags = contactTagMapper == null
                ? List.of()
                : contactTagMapper.findActiveByContactIdAndOwner(contactId, userId);

        return new ContactResponse(
                contactId,
                entity.getDisplayName(),
                entity.getRemark(),
                channelTypes,
                lastMessageAt,
                lastText,
                messageCount,
                unreadCount,
                tags,
                identityResponses,
                contactMemoryQueryService == null
                        ? null
                        : contactMemoryQueryService.findForOwner(userId, contactId).orElse(null),
                List.of());
    }

    /**
     * Mark all messages across all of a contact's conversations as read.
     *
     * @param contactId the contact UUID
     */
    public void markAsRead(UUID userId, UUID contactId) {
        List<ConversationEntity> conversations =
                conversationMapper.listAccessibleForContact(contactId, userId);

        List<UUID> conversationIds = conversations.stream()
                .map(ConversationEntity::getId)
                .toList();

        if (!conversationIds.isEmpty()) {
            messageMapper.markRead(conversationIds);
        }
    }

    /**
     * Bind a phone number to a contact as a new phone identity.
     *
     * @param contactId   the contact UUID as a string
     * @param contactName optional display name for the contact
     * @param phoneNumber the raw phone number to normalize and bind
     * @return the bound contact id, normalized phone point, and display name
     * @throws IllegalArgumentException if phoneNumber or contactId is missing/invalid
     */
    public PhoneContactBindingResponse bindPhone(String contactId, String contactName,
                                                 String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("phoneNumber is required");
        }
        String digits = phoneNumber.replaceAll("[^0-9+]", "");
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        String phonePoint = "phone:" + digits;
        UUID contactUuid = null;
        if (contactId != null && !contactId.isBlank()) {
            contactUuid = UUID.fromString(contactId);
        }
        if (contactUuid == null) {
            throw new IllegalArgumentException("contactId is required for phone binding");
        }
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contactUuid);
        identity.setChannelType("phone");
        identity.setIdentityValue(phonePoint);
        identity.setNormalizedValue(digits);
        identity.setDisplayName(contactName != null ? contactName : phoneNumber);
        identity.setIsPrimary(false);
        identity.setVerifyStatus("unverified");
        identity.setCreatedAt(Instant.now());
        identity.setUpdatedAt(Instant.now());
        identity.setVersion(1L);
        Optional<ContactIdentityEntity> existing =
                contactIdentityMapper.findByNormalizedValue("phone", digits);
        if (existing.isPresent()) {
            ContactIdentityEntity found = existing.get();
            return new PhoneContactBindingResponse(
                    found.getContactId().toString(),
                    found.getIdentityValue(),
                    found.getDisplayName() != null ? found.getDisplayName() : phoneNumber);
        }
        contactIdentityMapper.insert(identity);
        return new PhoneContactBindingResponse(
                contactUuid.toString(), phonePoint, identity.getDisplayName());
    }

    public PhoneContactBindingResponse bindPhone(UUID ownerId, String contactId, String contactName,
                                                  String phoneNumber) {
        Objects.requireNonNull(ownerId, "ownerId");
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("phoneNumber is required");
        }
        UUID contactUuid = UUID.fromString(contactId);
        contactMapper.findByIdAndOwner(contactUuid, ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + contactUuid));
        String digits = phoneNumber.replaceAll("[^0-9+]", "");
        if (digits.startsWith("+")) digits = digits.substring(1);
        String scope = ownerId.toString();
        Optional<ContactIdentityEntity> existing = contactIdentityMapper
                .findByNormalizedValueInScope("phone", scope, digits);
        if (existing.isPresent()) {
            ContactIdentityEntity found = existing.get();
            return new PhoneContactBindingResponse(found.getContactId().toString(),
                    found.getIdentityValue(), found.getDisplayName());
        }
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setContactId(contactUuid);
        identity.setChannelType("phone");
        identity.setIdentityScope(scope);
        identity.setIdentityValue("phone:" + digits);
        identity.setNormalizedValue(digits);
        identity.setDisplayName(contactName != null ? contactName : phoneNumber);
        identity.setIsPrimary(false);
        identity.setVerifyStatus("unverified");
        identity.setSource("manual");
        identity.setCreatedAt(Instant.now());
        identity.setUpdatedAt(Instant.now());
        identity.setVersion(1L);
        if (contactIdentityMapper.insertIfAbsent(identity) != 1) {
            throw new IllegalArgumentException("Phone identity already exists");
        }
        return new PhoneContactBindingResponse(contactUuid.toString(),
                identity.getIdentityValue(), identity.getDisplayName());
    }

    private static int clampSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    public static boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN"));
    }
}
