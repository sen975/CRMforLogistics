package com.crmforlogistics.messagecenter.service.aitopic;

import java.util.Objects;
import java.util.UUID;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.entity.WeComSourceConversationEntity;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceConversationMapper;
import org.springframework.stereotype.Service;

@Service
public class AiTopicOwnerService {
    private final WeComSourceConversationMapper sourceConversations;
    private final ContactIdentityMapper identities;

    public AiTopicOwnerService(WeComSourceConversationMapper sourceConversations, ContactIdentityMapper identities) {
        this.sourceConversations = sourceConversations;
        this.identities = identities;
    }

    public static OwnerRef contact(UUID contactId) {
        return new OwnerRef("CONTACT", Objects.requireNonNull(contactId, "contactId"));
    }

    public static OwnerRef group(UUID sourceConversationId) {
        return new OwnerRef("WECOM_GROUP", Objects.requireNonNull(sourceConversationId, "sourceConversationId"));
    }

    public OwnerRef resolveWeComConversation(UUID sourceConversationId) {
        WeComSourceConversationEntity conversation = sourceConversations.selectById(sourceConversationId);
        if (conversation == null) throw new IllegalArgumentException("source conversation not found");
        if ("GROUP".equalsIgnoreCase(conversation.getConversationType())) return group(sourceConversationId);
        var identity = identities.selectById(conversation.getContactIdentityId());
        if (identity == null || identity.getContactId() == null) {
            throw new IllegalArgumentException("source conversation contact identity not found");
        }
        return contact(identity.getContactId());
    }

    public OwnerRef resolveConversation(ConversationEntity conversation) {
        Objects.requireNonNull(conversation, "conversation");
        if (conversation.getSourceConversationId() != null) {
            return resolveWeComConversation(conversation.getSourceConversationId());
        }
        UUID identityId = conversation.getContactIdentityId();
        if (identityId == null) throw new IllegalArgumentException("conversation contact identity not found");
        var identity = identities.selectById(identityId);
        if (identity == null || identity.getContactId() == null) {
            throw new IllegalArgumentException("conversation contact identity not found");
        }
        return contact(identity.getContactId());
    }

    public OwnerRef resolveContactAnchor(String contactAnchorPointId) {
        if (contactAnchorPointId == null || contactAnchorPointId.isBlank()) {
            throw new IllegalArgumentException("contact anchor invalid");
        }
        if (contactAnchorPointId.startsWith("phone:")) {
            String normalizedPhone = contactAnchorPointId.substring("phone:".length());
            if (normalizedPhone.isBlank()) throw new IllegalArgumentException("contact anchor invalid");
            var identity = identities.findByNormalizedValue("phone", normalizedPhone)
                    .orElseThrow(() -> new IllegalArgumentException("phone contact identity not found"));
            if (identity.getContactId() == null) {
                throw new IllegalArgumentException("phone contact identity not found");
            }
            return contact(identity.getContactId());
        }
        if (!contactAnchorPointId.startsWith("contact:")) {
            throw new IllegalArgumentException("contact anchor invalid");
        }
        try {
            return contact(UUID.fromString(contactAnchorPointId.substring("contact:".length())));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("contact anchor invalid", e);
        }
    }

    public record OwnerRef(String type, UUID id) {
        public OwnerRef {
            if (!"CONTACT".equals(type) && !"WECOM_GROUP".equals(type)) {
                throw new IllegalArgumentException("unsupported owner type");
            }
            Objects.requireNonNull(id, "id");
        }
    }
}
