package com.crmforlogistics.messagecenter.service.contact;

import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicOwnerActivityService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContactGroupServiceTopicReconciliationTest {
    @Test
    void mergeArchivesSourceTopicsAndRecordsTargetActivity() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ContactMapper contacts = mock(ContactMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicOwnerActivityService activities = mock(AiTopicOwnerActivityService.class);
        ContactEntity source = new ContactEntity();
        source.setId(sourceId);
        when(contacts.selectById(sourceId)).thenReturn(source);

        ContactGroupService service = new ContactGroupService(contacts, identities, topics, activities);

        service.merge(sourceId, targetId, UUID.randomUUID());

        verify(topics).archiveReadyByContact(sourceId);
        verify(activities).recordActivity(
                eq(new com.crmforlogistics.messagecenter.service.aitopic.AiTopicOwnerService.OwnerRef("CONTACT", targetId)),
                any());
    }
}
