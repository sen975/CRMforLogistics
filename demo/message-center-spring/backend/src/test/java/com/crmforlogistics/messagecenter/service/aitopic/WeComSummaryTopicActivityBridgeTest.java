package com.crmforlogistics.messagecenter.service.aitopic;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.entity.WeComSourceConversationEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicOwnerActivityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceConversationMapper;
import com.crmforlogistics.messagecenter.service.wecom.WeComMessageSummaryRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeComSummaryTopicActivityBridgeTest {
    @Test
    void directSummaryResolvesToContactAndGroupSummaryToGroup() {
        UUID contact = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        assertThat(WeComSummaryTopicActivityBridge.ownerFor("DIRECT", contact, group))
                .isEqualTo(new AiTopicOwnerService.OwnerRef("CONTACT", contact));
        assertThat(WeComSummaryTopicActivityBridge.ownerFor("GROUP", contact, group))
                .isEqualTo(new AiTopicOwnerService.OwnerRef("WECOM_GROUP", group));
    }

    @Test
    void directSummaryDereferencesContactIdentityToContactOwner() {
        UUID sourceId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        WeComSourceConversationMapper conversations = mock(WeComSourceConversationMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        WeComSourceConversationEntity source = new WeComSourceConversationEntity();
        source.setId(sourceId);
        source.setConversationType("DIRECT");
        source.setContactIdentityId(identityId);
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(identityId);
        identity.setContactId(contactId);
        when(conversations.selectById(sourceId)).thenReturn(source);
        when(identities.selectById(identityId)).thenReturn(identity);
        var bridge = new WeComSummaryTopicActivityBridge(mock(WeComMessageSummaryRepository.class), conversations,
                identities, mock(AiTopicOwnerActivityMapper.class), 360);

        assertThat(bridge.resolveOwner(sourceId))
                .isEqualTo(new AiTopicOwnerService.OwnerRef("CONTACT", contactId));
    }
}
