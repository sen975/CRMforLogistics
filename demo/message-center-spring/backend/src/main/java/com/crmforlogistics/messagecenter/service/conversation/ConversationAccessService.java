package com.crmforlogistics.messagecenter.service.conversation;

import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
public class ConversationAccessService {
    private final ConversationMapper conversationMapper;

    public ConversationAccessService(ConversationMapper conversationMapper) {
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
    }

    public ConversationEntity requireAccessible(UUID conversationId,
                                                 UUID channelAccountId,
                                                 UUID actorUserId) {
        return accessible(conversationId, channelAccountId, actorUserId, false);
    }

    public ConversationEntity lockForMessage(UUID conversationId,
                                             UUID channelAccountId,
                                             UUID actorUserId) {
        return accessible(conversationId, channelAccountId, actorUserId, true);
    }

    private ConversationEntity accessible(UUID conversationId,
                                          UUID channelAccountId,
                                          UUID actorUserId,
                                          boolean lockForUpdate) {
        ConversationEntity conversation = conversationMapper.findAccessibleForMessage(
                conversationId, channelAccountId, actorUserId, lockForUpdate);
        if (conversation == null) {
            throw new SecurityException("CHATAPP_CONVERSATION_FORBIDDEN");
        }
        return conversation;
    }
}
