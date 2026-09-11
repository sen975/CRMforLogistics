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

@Service
public class ContactMemoryQueryService {
    private static final int MAX_LABELS = 100;

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
        if (ownerUserId == null || contactId == null
                || contacts.findByIdAndOwner(contactId, ownerUserId).isEmpty()) {
            return Optional.empty();
        }

        ContactMemoryStateEntity state = states.findByOwnerAndContact(ownerUserId, contactId)
                .orElse(null);
        ContactProfileVersionEntity profile = memory.findCurrentProfile(ownerUserId, contactId);
        ContactMemoryAttemptEntity attempt = memory.findLatestSuccessfulAttempt(ownerUserId, contactId);
        List<ContactTagResponse> manual = humanTags.findActiveByContactIdAndOwner(contactId, ownerUserId);
        List<ContactMemoryResponse.AiTag> ai = memory.listVisibleLabels(
                        ownerUserId, contactId, MAX_LABELS)
                .stream()
                .filter(label -> "ACTIVE".equals(label.getStatus())
                        || "STALE".equals(label.getStatus()))
                .map(ContactMemoryQueryService::toAiTag)
                .toList();

        return Optional.of(new ContactMemoryResponse(
                toProfile(profile),
                manual,
                ai,
                state == null ? "CLEAN" : safeState(state.getStatus()),
                attempt == null ? null : attempt.getCompletedAt(),
                state == null ? null : safeFailureCode(state.getLastFailureCode()),
                hasPendingInbound(state)));
    }

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

    private static String safeState(String value) {
        return value == null || value.isBlank() ? "CLEAN" : value;
    }

    private static String safeFailureCode(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
