package com.crmforlogistics.messagecenter.service.aitopic;

import org.junit.jupiter.api.Test;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.WeComSourceConversationEntity;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceConversationMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiTopicOwnerActivityServiceTest {
    @Test
    void quietDeadlineMovesForwardButNeverBackForLateOlderEvent() {
        Instant first = Instant.parse("2026-09-01T00:00:00Z");
        var state = AiTopicOwnerActivityService.advance(null, first, 360);

        var late = AiTopicOwnerActivityService.advance(state, first.minusSeconds(120), 360);

        assertThat(late.latestEventAt()).isEqualTo(first);
        assertThat(late.quietDeadline()).isEqualTo(first.plusSeconds(360));
        assertThat(late.activityVersion()).isEqualTo(state.activityVersion());
    }

    @Test
    void newerEventExtendsQuietDeadlineAndVersion() {
        Instant first = Instant.parse("2026-09-01T00:00:00Z");
        var state = AiTopicOwnerActivityService.advance(null, first, 360);
        var newer = AiTopicOwnerActivityService.advance(state, first.plusSeconds(10), 360);

        assertThat(newer.latestEventAt()).isEqualTo(first.plusSeconds(10));
        assertThat(newer.quietDeadline()).isEqualTo(first.plusSeconds(370));
        assertThat(newer.activityVersion()).isEqualTo(state.activityVersion() + 1);
    }

    @Test
    void directAndGroupOwnersAreDistinct() {
        UUID id = UUID.randomUUID();
        assertThat(AiTopicOwnerService.contact(id)).isEqualTo(new AiTopicOwnerService.OwnerRef("CONTACT", id));
        assertThat(AiTopicOwnerService.group(id)).isEqualTo(new AiTopicOwnerService.OwnerRef("WECOM_GROUP", id));
    }

    @Test
    void directWeComConversationResolvesIdentityToItsContact() {
        WeComSourceConversationMapper conversations = mock(WeComSourceConversationMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        UUID conversationId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        WeComSourceConversationEntity conversation = new WeComSourceConversationEntity();
        conversation.setId(conversationId);
        conversation.setConversationType("DIRECT");
        conversation.setContactIdentityId(identityId);
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(identityId);
        identity.setContactId(contactId);
        when(conversations.selectById(conversationId)).thenReturn(conversation);
        when(identities.selectById(identityId)).thenReturn(identity);

        var owner = new AiTopicOwnerService(conversations, identities).resolveWeComConversation(conversationId);

        assertThat(owner).isEqualTo(AiTopicOwnerService.contact(contactId));
    }

    @Test
    void phoneCallAnchorResolvesToItsCurrentContactOwner() {
        WeComSourceConversationMapper conversations = mock(WeComSourceConversationMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        UUID contactId = UUID.randomUUID();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setContactId(contactId);
        when(identities.findByNormalizedValue("phone", "60123456789"))
                .thenReturn(Optional.of(identity));

        var owner = new AiTopicOwnerService(conversations, identities)
                .resolveContactAnchor("phone:60123456789");

        assertThat(owner).isEqualTo(AiTopicOwnerService.contact(contactId));
    }
}
