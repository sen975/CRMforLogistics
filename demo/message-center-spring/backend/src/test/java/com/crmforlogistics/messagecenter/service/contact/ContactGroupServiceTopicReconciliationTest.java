package com.crmforlogistics.messagecenter.service.contact;

import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicOwnerActivityService;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicContactMergeReconciler;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static java.util.Optional.of;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void mergeCandidateQueryOnlyIncludesPersonalReadyAndPendingTopics() throws Exception {
        Method method = AiTopicMapper.class.getMethod("listContactMergeCandidates", UUID.class);
        String statement = String.join(" ", method.getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        org.assertj.core.api.Assertions.assertThat(statement)
                .contains("owner_type='CONTACT'")
                .contains("status in ('READY','REVIEW_PENDING')")
                .doesNotContain("STORED");
    }

    @Test
    void pendingMergeTransferKeepsReviewPendingSemantics() throws Exception {
        Method method = AiTopicMapper.class.getMethod("transferReviewPendingByContact", UUID.class, UUID.class);
        String statement = String.join(" ", method.getAnnotation(org.apache.ibatis.annotations.Update.class).value());
        org.assertj.core.api.Assertions.assertThat(statement)
                .contains("status='REVIEW_PENDING'")
                .contains("review_origin='MERGE_SOURCE'")
                .contains("owner_type='CONTACT'");
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
        ContactEntity target = new ContactEntity();
        target.setId(targetId);
        UUID userId = UUID.randomUUID();
        when(contacts.findAccessibleById(sourceId, userId, false)).thenReturn(of(source));
        when(contacts.findAccessibleById(targetId, userId, false)).thenReturn(of(target));

        ContactGroupService service = new ContactGroupService(contacts, identities, topics, activities);

        service.merge(sourceId, targetId, userId);

        verify(topics).transferReadyByContact(sourceId, targetId);
        verify(topics, org.mockito.Mockito.never()).archiveReadyByContact(sourceId);
        verify(activities).recordActivity(
                eq(new com.crmforlogistics.messagecenter.service.aitopic.AiTopicOwnerService.OwnerRef("CONTACT", targetId)),
                any());
    }

    @Test
    void mergeDelegatesTopicReconciliationToTopicOwner() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ContactMapper contacts = mock(ContactMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicOwnerActivityService activities = mock(AiTopicOwnerActivityService.class);
        AiTopicContactMergeReconciler reconciler = mock(AiTopicContactMergeReconciler.class);
        ContactEntity source = new ContactEntity();
        source.setId(sourceId);
        ContactEntity target = new ContactEntity();
        target.setId(targetId);
        UUID userId = UUID.randomUUID();
        when(contacts.findAccessibleById(sourceId, userId, false)).thenReturn(of(source));
        when(contacts.findAccessibleById(targetId, userId, false)).thenReturn(of(target));

        ContactGroupService service = new ContactGroupService(contacts, identities, null, topics, null,
                activities, null, reconciler);

        service.merge(sourceId, targetId, userId);

        verify(reconciler).reconcileAfterContactMerge(sourceId, targetId, userId);
        verify(topics, org.mockito.Mockito.never()).transferReadyByContact(sourceId, targetId);
    }

    @Test
    void topicReconciliationFailurePreventsContactMergeStateFromBeingSaved() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ContactMapper contacts = mock(ContactMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        AiTopicContactMergeReconciler reconciler = mock(AiTopicContactMergeReconciler.class);
        ContactEntity source = new ContactEntity();
        source.setId(sourceId);
        ContactEntity target = new ContactEntity();
        target.setId(targetId);
        when(contacts.findAccessibleById(sourceId, userId, false)).thenReturn(of(source));
        when(contacts.findAccessibleById(targetId, userId, false)).thenReturn(of(target));
        doThrow(new com.crmforlogistics.messagecenter.service.aitopic.AiTopicException(
                "TOPIC_FUSION_UNAVAILABLE", false))
                .when(reconciler).reconcileAfterContactMerge(sourceId, targetId, userId);

        ContactGroupService service = new ContactGroupService(contacts, identities, null, null, null,
                null, null, reconciler);

        assertThatThrownBy(() -> service.merge(sourceId, targetId, userId))
                .isInstanceOf(com.crmforlogistics.messagecenter.service.aitopic.AiTopicException.class);
        verify(contacts, org.mockito.Mockito.never()).updateById(source);
    }
}
