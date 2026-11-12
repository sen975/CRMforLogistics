package com.crmforlogistics.messagecenter.service.conversation;

import com.crmforlogistics.messagecenter.dto.response.ConversationPreferenceResponse;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
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
        String displayName = authorize(userId, targetType, targetId);
        preferences.ensure(userId, targetType, targetId);
        var current = preferences.find(userId, targetType, targetId);
        boolean pinned = current == null || !Boolean.TRUE.equals(current.getPinned());
        if (preferences.setPinned(userId, targetType, targetId, pinned) != 1) {
            throw new IllegalStateException("Conversation preference was not updated");
        }
        return new ConversationPreferenceResponse(targetType, targetId, pinned,
                current != null && current.getHiddenAt() != null, displayName);
    }

    /**
     * 把一条会话设为置顶 / 取消置顶。<b>目标状态语义，不是切换。</b>
     *
     * <p>与 {@link #togglePinned} 的区别正好是「助手能不能用」的分界：切换语义要求调用方
     * 先知道当前状态（否则「置顶」与「取消置顶」会被执行成同一件事），而模型看不到当前状态。
     * 因此助手侧用这个入口，把「设成什么」写成明确参数，确认卡片上显示的就是将要发生的事。
     */
    @Transactional
    public ConversationPreferenceResponse setPinned(UUID userId, String targetType, UUID targetId, boolean pinned) {
        String displayName = authorize(userId, targetType, targetId);
        preferences.ensure(userId, targetType, targetId);
        if (preferences.setPinned(userId, targetType, targetId, pinned) != 1) {
            throw new IllegalStateException("Conversation preference was not updated");
        }
        ConversationPreferenceEntity current = preferences.find(userId, targetType, targetId);
        return new ConversationPreferenceResponse(targetType, targetId, pinned,
                current != null && current.getHiddenAt() != null, displayName);
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
        String displayName = authorize(userId, targetType, targetId);
        preferences.clearHidden(userId, targetType, targetId);
        ConversationPreferenceEntity current = preferences.find(userId, targetType, targetId);
        return new ConversationPreferenceResponse(targetType, targetId,
                current != null && Boolean.TRUE.equals(current.getPinned()),
                current != null && current.getHiddenAt() != null, displayName);
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

    /**
     * 授权校验，顺带把目标会话的<b>权威显示名</b>带回来。
     *
     * <p>返回名字而不是 {@code void}：这次查询本来就要把目标行读出来才能判断可见性
     * （{@code findAccessibleById} / {@code findAccessibleWeComGroup} 的产物里就带着名字），
     * 丢掉它意味着调用方要么显示 id、要么再查一遍 —— 前者我们已经踩过（卡片写名字、
     * 回话写 uuid），后者是同一份数据查两次。
     *
     * <p>名字可能为 {@code null}（行存在但名字为空），调用方据此退回显示 id。
     * 越权与不存在的处理一律不变，仍在这里 fail-closed。
     */
    private String authorize(UUID userId, String targetType, UUID targetId) {
        if (userId == null || targetId == null || targetType == null) {
            throw new IllegalArgumentException("conversation target is invalid");
        }
        return switch (targetType) {
            case "CONTACT" -> {
                // 不用 findAccessibleById(...).map(ContactEntity::getDisplayName)：Optional.map 在
                // 映射结果为 null 时会返回 empty，于是「会话存在、但没有显示名」会被 orElseThrow
                // 塌缩成「不存在」—— 授权判定因此从「放过无名行」变成「拒绝无名行」，这不是同一个
                // 判断，却只差一个 map 调用。存在性与取名必须分成两步。
                ContactEntity contact = contacts.findAccessibleById(targetId, userId,
                                ContactService.isCurrentUserAdmin())
                        .orElseThrow(() -> new IllegalArgumentException("Contact not found"));
                yield contact.getDisplayName();
            }
            case "WECOM_GROUP" -> {
                ConversationMapper.WeComSourceConversationAccessRow row =
                        conversations.findAccessibleWeComGroup(userId, targetId);
                if (row == null) {
                    throw new IllegalArgumentException("WeCom group not found");
                }
                yield row.displayName();
            }
            default -> throw new IllegalArgumentException("conversation target type is invalid");
        };
    }
}
