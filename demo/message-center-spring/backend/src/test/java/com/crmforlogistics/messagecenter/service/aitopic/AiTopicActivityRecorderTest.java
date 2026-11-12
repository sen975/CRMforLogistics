package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.ConversationEntity;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTopicActivityRecorderTest {
    @Test
    void recordsContactActivityFromPersistedConversationIdentity() {
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        AiTopicOwnerActivityService activities = mock(AiTopicOwnerActivityService.class);
        AiTopicOwnerService owners = mock(AiTopicOwnerService.class);
        UUID identityId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-09-01T08:00:00Z");
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(identityId);
        identity.setContactId(contactId);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setContactIdentityId(identityId);
        AiTopicOwnerService.OwnerRef owner = AiTopicOwnerService.contact(contactId);
        when(identities.selectById(identityId)).thenReturn(identity);
        when(owners.resolveConversation(conversation)).thenReturn(owner);

        new AiTopicActivityRecorder(owners, activities).recordConversation(conversation, occurredAt);

        verify(activities).recordActivity(owner, occurredAt);
    }

    @Test
    void recordsCallActivityFromContactAnchor() {
        AiTopicOwnerActivityService activities = mock(AiTopicOwnerActivityService.class);
        AiTopicOwnerService owners = mock(AiTopicOwnerService.class);
        UUID contactId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-09-01T08:00:00Z");
        AiTopicOwnerService.OwnerRef owner = AiTopicOwnerService.contact(contactId);
        when(owners.resolveContactAnchor("contact:" + contactId)).thenReturn(owner);

        new AiTopicActivityRecorder(owners, activities)
                .recordCall("contact:" + contactId, occurredAt);

        verify(activities).recordActivity(owner, occurredAt);
    }
}
