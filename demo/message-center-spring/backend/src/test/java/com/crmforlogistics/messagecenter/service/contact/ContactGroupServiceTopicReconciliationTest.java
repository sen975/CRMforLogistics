package com.crmforlogistics.messagecenter.service.contact;

import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicOwnerActivityService;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContactGroupServiceTopicReconciliationTest {
    @Test
    void transferContractMovesReadyTopicsIntoPendingReview() throws Exception {
        Method method = AiTopicMapper.class.getMethod("transferReadyByContact", UUID.class, UUID.class);
        String[] sql = method.getAnnotation(org.apache.ibatis.annotations.Update.class).value();
        String statement = String.join(" ", sql);
        org.assertj.core.api.Assertions.assertThat(statement)
                .contains("status='REVIEW_PENDING'", "review_origin='MERGE_SOURCE'", "review_source_contact_id");
        org.assertj.core.api.Assertions.assertThat(statement).contains("owner_type='CONTACT'");
    }

    @Test
    void mergeTransfersSourceTopicsAndRecordsTargetActivity() {
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

        verify(topics).transferReadyByContact(sourceId, targetId);
        verify(topics, org.mockito.Mockito.never()).archiveReadyByContact(sourceId);
        verify(activities).recordActivity(
                eq(new com.crmforlogistics.messagecenter.service.aitopic.AiTopicOwnerService.OwnerRef("CONTACT", targetId)),
                any());
    }
}
