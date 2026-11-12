package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.dto.response.ContactMemoryResponse;
import com.crmforlogistics.messagecenter.dto.response.ContactTagResponse;
import com.crmforlogistics.messagecenter.entity.ContactAiLabelEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryAttemptEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryStateEntity;
import com.crmforlogistics.messagecenter.entity.ContactProfileVersionEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.Instant;

@Service
public class ContactMemoryQueryService {
    private static final int DEFAULT_LABEL_PAGE_SIZE = 100;
    private static final int MAX_LABEL_PAGE_SIZE = 100;

    private final ContactMapper contacts;
    private final ContactMemoryMapper memory;
    private final ContactMemoryStateMapper states;
    private final ContactTagMapper humanTags;

    public ContactMemoryQueryService(ContactMapper contacts,
                                     ContactMemoryMapper memory,
                                     ContactMemoryStateMapper states,
                                     ContactTagMapper humanTags) {
        this.contacts = contacts;
        this.memory = memory;
        this.states = states;
        this.humanTags = humanTags;
    }

    public Optional<ContactMemoryResponse> findForOwner(UUID ownerUserId, UUID contactId) {
        return findForOwner(ownerUserId, contactId, DEFAULT_LABEL_PAGE_SIZE, null);
    }

    public Optional<ContactMemoryResponse> findForOwner(UUID ownerUserId,
                                                        UUID contactId,
                                                        Integer requestedLimit,
                                                        String cursor) {
        if (ownerUserId == null || contactId == null
                || contacts.findByIdAndOwner(contactId, ownerUserId).isEmpty()) {
            return Optional.empty();
        }

        int limit = requestedLimit == null
                ? DEFAULT_LABEL_PAGE_SIZE
                : Math.max(1, Math.min(requestedLimit, MAX_LABEL_PAGE_SIZE));
        LabelCursor after = decodeCursor(cursor);

        ContactMemoryStateEntity state = states.findByOwnerAndContact(ownerUserId, contactId)
                .orElse(null);
        ContactProfileVersionEntity profile = memory.findCurrentProfile(ownerUserId, contactId);
        ContactMemoryAttemptEntity attempt = memory.findLatestSuccessfulAttempt(ownerUserId, contactId);
        List<ContactTagResponse> manual = humanTags.findActiveByContactIdAndOwner(contactId, ownerUserId);
        List<ContactAiLabelEntity> labels = memory.listVisibleLabelsPage(
                        ownerUserId, contactId, after == null ? null : after.lastSeenAt(),
                        after == null ? null : after.id(), limit + 1);
        boolean hasMore = labels.size() > limit;
        if (hasMore) {
            labels = labels.subList(0, limit);
        }
        List<ContactMemoryResponse.AiTag> ai = labels
                .stream()
                .map(ContactMemoryQueryService::toAiTag)
                .toList();
        String nextCursor = hasMore && !labels.isEmpty()
                ? encodeCursor(labels.get(labels.size() - 1)) : null;

        return Optional.of(new ContactMemoryResponse(
                toProfile(profile),
                manual,
                ai,
                ContactMemoryModels.memoryState(state == null ? null : state.getStatus()),
                attempt == null ? null : attempt.getCompletedAt(),
                state == null ? null : safeFailureCode(state.getLastFailureCode()),
                hasPendingInbound(state), nextCursor, hasMore));
    }

    private static String encodeCursor(ContactAiLabelEntity label) {
        String raw = label.getLastSeenAt() + "|" + label.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static LabelCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("invalid memory label cursor");
            }
            return new LabelCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid memory label cursor", exception);
        }
    }

    private record LabelCursor(Instant lastSeenAt, UUID id) { }

    private static ContactMemoryResponse.Profile toProfile(ContactProfileVersionEntity profile) {
        if (profile == null) return null;
        return new ContactMemoryResponse.Profile(
                profile.getId(),
                profile.getVersion() == null ? 0L : profile.getVersion(),
                profile.getContent(),
                profile.getCreatedAt());
    }

    private static ContactMemoryResponse.AiTag toAiTag(ContactAiLabelEntity label) {
        return new ContactMemoryResponse.AiTag(
                label.getId(),
                label.getDisplayName(),
                label.getCategory(),
                label.getColorToken(),
                label.getStatus(),
                label.getConfidence());
    }

    private static boolean hasPendingInbound(ContactMemoryStateEntity state) {
        if (state == null || state.getStatus() == null) return false;
        return switch (state.getStatus()) {
            case "DIRTY", "PROCESSING", "RETRY_WAIT", "FAILED" -> true;
            default -> false;
        };
    }

    private static String safeFailureCode(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
