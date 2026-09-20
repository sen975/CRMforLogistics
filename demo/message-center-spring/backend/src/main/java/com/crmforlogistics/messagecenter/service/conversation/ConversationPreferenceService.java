package com.crmforlogistics.messagecenter.service.conversation;

import com.crmforlogistics.messagecenter.dto.response.ConversationPreferenceResponse;
import com.crmforlogistics.messagecenter.entity.ConversationPreferenceEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationPreferenceMapper;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationPreferenceService {
    private static final int MAX_REORDERABLE_CONVERSATIONS = 200;
    private final ConversationPreferenceMapper preferences;
    private final ContactMapper contacts;
    private final ConversationMapper conversations;

    public ConversationPreferenceService(ConversationPreferenceMapper preferences,
                                          ContactMapper contacts,
                                          ConversationMapper conversations) {
        this.preferences = preferences;
        this.contacts = contacts;
        this.conversations = conversations;
    }

    @Transactional
    public ConversationPreferenceResponse togglePinned(UUID userId, String targetType, UUID targetId) {
        authorize(userId, targetType, targetId);
        preferences.ensure(userId, targetType, targetId);
        var current = preferences.find(userId, targetType, targetId);
        boolean pinned = current == null || !Boolean.TRUE.equals(current.getPinned());
        if (preferences.setPinned(userId, targetType, targetId, pinned) != 1) {
            throw new IllegalStateException("Conversation preference was not updated");
        }
        return new ConversationPreferenceResponse(targetType, targetId, pinned,
                current != null && current.getHiddenAt() != null);
    }

    @Transactional
    public void hide(UUID userId, String targetType, UUID targetId) {
        authorize(userId, targetType, targetId);
        preferences.ensure(userId, targetType, targetId);
        if (preferences.setHiddenNow(userId, targetType, targetId) != 1) {
            throw new IllegalStateException("Conversation preference was not hidden");
        }
    }

    /**
     * Deliberately opening a conversation is an explicit intent to keep it reachable,
     * so it clears a previously stored hidden preference. Idempotent: it never creates
     * a preference row for a conversation the current account never personalised, and
     * it leaves pinned state and sort rank untouched.
     */
    @Transactional
    public ConversationPreferenceResponse restore(UUID userId, String targetType, UUID targetId) {
        authorize(userId, targetType, targetId);
        preferences.clearHidden(userId, targetType, targetId);
        ConversationPreferenceEntity current = preferences.find(userId, targetType, targetId);
        return new ConversationPreferenceResponse(targetType, targetId,
                current != null && Boolean.TRUE.equals(current.getPinned()),
                current != null && current.getHiddenAt() != null);
    }

    @Transactional
    public void reorder(UUID userId, String sourceType, UUID sourceId,
                        String targetType, UUID targetId, String placement) {
        if (sourceId == null || targetId == null || sourceId.equals(targetId)) {
            throw new IllegalArgumentException("conversation order target is invalid");
        }
        boolean before = "BEFORE".equals(placement);
        if (!before && !"AFTER".equals(placement)) {
            throw new IllegalArgumentException("conversation order placement is invalid");
        }
        authorize(userId, sourceType, sourceId);
        authorize(userId, targetType, targetId);
        preferences.lockForUser(userId);

        List<ConversationMapper.UnifiedConversationRow> current = conversations.listUnified(
                userId, null, false, null, null, null, null, MAX_REORDERABLE_CONVERSATIONS + 1);
        if (current.size() > MAX_REORDERABLE_CONVERSATIONS) {
            throw new IllegalStateException("Too many conversations to reorder");
        }
        var source = find(current, sourceType, sourceId);
        var target = find(current, targetType, targetId);
        if (source == null || target == null) {
            throw new IllegalArgumentException("conversation order target is not visible");
        }
        if (source.pinned() != target.pinned()) {
            throw new IllegalArgumentException("pinned and unpinned conversations cannot be reordered together");
        }

        List<ConversationMapper.UnifiedConversationRow> ordered = new ArrayList<>(current);
        ordered.remove(source);
        int targetIndex = ordered.indexOf(target);
        ordered.add(before ? targetIndex : targetIndex + 1, source);

        preferences.clearSortRanks(userId);
        for (int index = 0; index < ordered.size(); index++) {
            var item = ordered.get(index);
            preferences.ensure(userId, item.type(), item.id());
            if (preferences.setSortRank(userId, item.type(), item.id(), index) != 1) {
                throw new IllegalStateException("Conversation order was not updated");
            }
        }
    }

    private static ConversationMapper.UnifiedConversationRow find(
            List<ConversationMapper.UnifiedConversationRow> rows, String type, UUID id) {
        if (type == null || id == null) return null;
        return rows.stream().filter(row -> type.equals(row.type()) && id.equals(row.id()))
                .findFirst().orElse(null);
    }

    private void authorize(UUID userId, String targetType, UUID targetId) {
        if (userId == null || targetId == null || targetType == null) {
            throw new IllegalArgumentException("conversation target is invalid");
        }
        switch (targetType) {
            case "CONTACT" -> contacts.findAccessibleById(targetId, userId,
                    ContactService.isCurrentUserAdmin()).orElseThrow(() -> new IllegalArgumentException("Contact not found"));
            case "WECOM_GROUP" -> {
                if (conversations.findAccessibleWeComGroup(userId, targetId) == null) {
                    throw new IllegalArgumentException("WeCom group not found");
                }
            }
            default -> throw new IllegalArgumentException("conversation target type is invalid");
        }
    }
}
