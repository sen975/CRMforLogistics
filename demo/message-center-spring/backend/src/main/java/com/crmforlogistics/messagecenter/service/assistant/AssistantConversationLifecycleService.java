package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.ConditionalOnAssistantEnabled;
import com.crmforlogistics.messagecenter.entity.AssistantConversationEntity;
import com.crmforlogistics.messagecenter.mapper.AssistantConversationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 唯一拥有助手会话状态、归属和 30 天过期门禁的领域服务。 */
@Component
@ConditionalOnAssistantEnabled
public class AssistantConversationLifecycleService {
    public static final Duration INACTIVITY_TTL = Duration.ofDays(30);
    private static final String ACTIVE = "ACTIVE";
    private static final String ARCHIVED = "ARCHIVED";
    private static final String EXPIRED = "EXPIRED";
    private static final String DELETED = "DELETED";

    private final AssistantConversationMapper mapper;
    private final Clock clock;

    @Autowired
    public AssistantConversationLifecycleService(AssistantConversationMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 发送前门禁；不存在的前端会话号会注册为新的 ACTIVE 会话。 */
    @Transactional
    public void requireActive(UUID userId, UUID conversationId) {
        if (userId == null || conversationId == null) return;
        Instant now = clock.instant();
        AssistantConversationEntity conversation = mapper.findOwned(userId, conversationId);
        if (conversation == null) {
            mapper.archiveActive(userId, now);
            mapper.insertActive(conversationId, userId, now);
            return;
        }
        expireIfStale(userId, conversationId, conversation, now);
        if (EXPIRED.equals(conversation.getStatus())) {
            throw new AssistantException(AssistantException.CONVERSATION_EXPIRED,
                    "这段对话已超过 30 天未活动，不能继续发送");
        }
        if (DELETED.equals(conversation.getStatus())) {
            throw new AssistantException(AssistantException.CONVERSATION_DELETED, "这段对话已删除");
        }
        if (!ACTIVE.equals(conversation.getStatus())) {
            throw new AssistantException(AssistantException.CONVERSATION_NOT_ACTIVE,
                    "请先重新打开这段归档对话");
        }
        mapper.touchActive(userId, conversationId, now);
    }

    public void touch(UUID userId, UUID conversationId) {
        if (userId == null || conversationId == null) return;
        mapper.touchActive(userId, conversationId, clock.instant());
    }

    @Transactional
    public UUID createNew(UUID userId, UUID conversationId) {
        requireIdentity(userId, conversationId);
        Instant now = clock.instant();
        mapper.archiveActive(userId, now);
        if (mapper.insertActive(conversationId, userId, now) != 1) {
            throw new AssistantException(AssistantException.CONVERSATION_ALREADY_EXISTS,
                    "这个会话号已经使用，请开始一段新对话");
        }
        return conversationId;
    }

    @Transactional
    public void open(UUID userId, UUID conversationId) {
        requireIdentity(userId, conversationId);
        Instant now = clock.instant();
        AssistantConversationEntity target = requireOwned(userId, conversationId);
        expireIfStale(userId, conversationId, target, now);
        if (EXPIRED.equals(target.getStatus())) {
            throw new AssistantException(AssistantException.CONVERSATION_EXPIRED,
                    "这段对话已超过 30 天未活动，不能恢复");
        }
        if (DELETED.equals(target.getStatus())) {
            throw new AssistantException(AssistantException.CONVERSATION_DELETED, "这段对话已删除");
        }
        if (ACTIVE.equals(target.getStatus())) return;
        mapper.archiveActive(userId, now);
        if (mapper.activateArchived(userId, conversationId, now) != 1) {
            throw new AssistantException(AssistantException.CONVERSATION_NOT_FOUND, "这段对话不存在或已失效");
        }
    }

    @Transactional
    public void delete(UUID userId, UUID conversationId) {
        requireIdentity(userId, conversationId);
        Instant now = clock.instant();
        AssistantConversationEntity target = requireOwned(userId, conversationId);
        expireIfStale(userId, conversationId, target, now);
        if (EXPIRED.equals(target.getStatus()) || DELETED.equals(target.getStatus())) {
            throw new AssistantException(AssistantException.CONVERSATION_NOT_FOUND, "这段对话不存在或已失效");
        }
        if (mapper.markDeleted(userId, conversationId, now) != 1) {
            throw new AssistantException(AssistantException.CONVERSATION_NOT_FOUND, "这段对话不存在或已失效");
        }
    }

    @Transactional
    public List<AssistantConversationEntity> listVisible(UUID userId) {
        if (userId == null) return List.of();
        expireStale();
        return mapper.listVisible(userId);
    }

    public UUID latestVisibleId(UUID userId) {
        if (userId == null) return null;
        expireStale();
        return mapper.latestVisibleId(userId);
    }

    public void requireReadable(UUID userId, UUID conversationId) {
        requireIdentity(userId, conversationId);
        Instant now = clock.instant();
        AssistantConversationEntity target = requireOwned(userId, conversationId);
        expireIfStale(userId, conversationId, target, now);
        if (EXPIRED.equals(target.getStatus())) {
            throw new AssistantException(AssistantException.CONVERSATION_EXPIRED,
                    "这段对话已超过 30 天未活动，不能读取");
        }
        if (DELETED.equals(target.getStatus())) {
            throw new AssistantException(AssistantException.CONVERSATION_DELETED, "这段对话已删除");
        }
    }

    public int expireStale() {
        Instant now = clock.instant();
        return mapper.expireStale(now, now.minus(INACTIVITY_TTL));
    }

    private AssistantConversationEntity requireOwned(UUID userId, UUID conversationId) {
        AssistantConversationEntity target = mapper.findOwned(userId, conversationId);
        if (target == null) {
            throw new AssistantException(AssistantException.CONVERSATION_NOT_FOUND, "这段对话不存在或已失效");
        }
        return target;
    }

    private void expireIfStale(UUID userId, UUID conversationId,
                               AssistantConversationEntity conversation, Instant now) {
        if ((ACTIVE.equals(conversation.getStatus()) || ARCHIVED.equals(conversation.getStatus()))
                && conversation.getLastActivityAt() != null
                && conversation.getLastActivityAt().isBefore(now.minus(INACTIVITY_TTL))) {
            int expired = mapper.markExpired(userId, conversationId, now, now.minus(INACTIVITY_TTL));
            if (expired == 1) {
                conversation.setStatus(EXPIRED);
                return;
            }
            AssistantConversationEntity refreshed = mapper.findOwned(userId, conversationId);
            if (refreshed != null) {
                conversation.setStatus(refreshed.getStatus());
                conversation.setLastActivityAt(refreshed.getLastActivityAt());
            }
        }
    }

    private static void requireIdentity(UUID userId, UUID conversationId) {
        if (userId == null) throw new SecurityException("Not authenticated");
        if (conversationId == null) {
            throw new AssistantException(AssistantException.CONVERSATION_NOT_FOUND, "会话号不能为空");
        }
    }
}
