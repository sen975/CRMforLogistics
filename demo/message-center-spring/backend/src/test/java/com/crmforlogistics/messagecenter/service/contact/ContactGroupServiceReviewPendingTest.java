package com.crmforlogistics.messagecenter.service.contact;

import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.entity.AiTopicItemEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicItemMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicSplitReconciler;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContactGroupServiceReviewPendingTest {

    @Test
    void topicMapperListsOnlyPersonalReadyAndStoredTopicsForSplit() throws Exception {
        Method method = AiTopicMapper.class.getMethod("listSplitCandidates", java.util.UUID.class);
        String statement = String.join(" ", method.getAnnotation(Select.class).value());
        assertThat(statement)
                .contains("owner_type='CONTACT'")
                .contains("status in ('READY','STORED')")
                .doesNotContain("WECOM_GROUP");
    }

    @Test
    void topicItemMapperPartitionsByMovedIdentityAcrossMessagesCallsAndWecomSummaries() throws Exception {
        Method method = AiTopicItemMapper.class.getMethod("listByTopicAndIdentity", java.util.UUID.class, java.util.UUID.class);
        String statement = String.join(" ", method.getAnnotation(Select.class).value());
        assertThat(statement)
                .contains("messages")
                .contains("call_records")
                .contains("wecom_message_summary_jobs")
                .contains("contact_identity_id");
    }

    @Test
    void splitMutationsCreatePendingSourceAndArchiveOnlyWhenNoItemsRemain() throws Exception {
        Method create = AiTopicMapper.class.getMethod("createSplitPendingTopic", java.util.UUID.class,
                java.util.UUID.class, String.class, String.class, String.class, String.class,
                java.util.UUID.class, java.util.UUID.class, java.util.UUID.class,
                java.time.Instant.class, java.time.Instant.class);
        String createSql = String.join(" ", create.getAnnotation(org.apache.ibatis.annotations.Insert.class).value());
        assertThat(createSql)
                .contains("REVIEW_PENDING")
                .contains("SPLIT_SOURCE")
                .contains("'CONTACT'");

        Method archive = AiTopicMapper.class.getMethod("archiveIfEmptyAfterSplit", java.util.UUID.class);
        String archiveSql = String.join(" ", archive.getAnnotation(Update.class).value());
        assertThat(archiveSql).contains("status='ARCHIVED'").contains("not exists");
    }

    @Test
    void mixedReadyTopicIsRecomputedAfterMovedSourcesLeaveTheOriginal() {
        UUID sourceContactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(identityId);
        identity.setContactId(sourceContactId);
        AiTopicEntity topic = new AiTopicEntity();
        topic.setId(topicId);
        topic.setStatus("READY");
        topic.setFirstOccurredAt(Instant.parse("2026-09-01T00:00:00Z"));
        topic.setLastOccurredAt(Instant.parse("2026-09-01T01:00:00Z"));
        AiTopicItemEntity moved = new AiTopicItemEntity();
        moved.setId(UUID.randomUUID());
        moved.setTopicId(topicId);
        moved.setOccurredAt(topic.getFirstOccurredAt());

        ContactMapper contacts = mock(ContactMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicItemMapper items = mock(AiTopicItemMapper.class);
        AiTopicSplitReconciler splitReconciler = mock(AiTopicSplitReconciler.class);
        when(identities.selectById(identityId)).thenReturn(identity);
        when(contacts.findAccessibleById(org.mockito.ArgumentMatchers.eq(sourceContactId),
                org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(Optional.of(sourceContact(sourceContactId)));
        when(identities.updateContactIdForIdentity(any(), org.mockito.ArgumentMatchers.eq(identityId))).thenReturn(1);
        when(topics.listSplitCandidates(sourceContactId)).thenReturn(List.of(topic));
        when(items.listByTopicAndIdentity(topicId, identityId)).thenReturn(List.of(moved));
        when(items.countByTopic(topicId)).thenReturn(2);

        ContactGroupService service = new ContactGroupService(contacts, identities, null, topics, items,
                null, splitReconciler);

        service.split(identityId, "拆分联系人", UUID.randomUUID());

        verify(items).updateById(moved);
        verify(splitReconciler).recomputeAfterSourceSplit(topicId);
    }

    @Test
    void mixedStoredTopicKeepsItsRepositorySnapshotWithoutAutomaticRecompute() {
        UUID sourceContactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        ContactIdentityEntity identity = new ContactIdentityEntity();
        identity.setId(identityId);
        identity.setContactId(sourceContactId);
        AiTopicEntity topic = new AiTopicEntity();
        topic.setId(topicId);
        topic.setStatus("STORED");
        topic.setFirstOccurredAt(Instant.parse("2026-09-01T00:00:00Z"));
        topic.setLastOccurredAt(Instant.parse("2026-09-01T01:00:00Z"));
        AiTopicItemEntity moved = new AiTopicItemEntity();
        moved.setId(UUID.randomUUID());
        moved.setTopicId(topicId);
        moved.setOccurredAt(topic.getFirstOccurredAt());

        ContactMapper contacts = mock(ContactMapper.class);
        ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicItemMapper items = mock(AiTopicItemMapper.class);
        AiTopicSplitReconciler splitReconciler = mock(AiTopicSplitReconciler.class);
        when(identities.selectById(identityId)).thenReturn(identity);
        when(contacts.findAccessibleById(org.mockito.ArgumentMatchers.eq(sourceContactId),
                org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(Optional.of(sourceContact(sourceContactId)));
        when(identities.updateContactIdForIdentity(any(), org.mockito.ArgumentMatchers.eq(identityId))).thenReturn(1);
        when(topics.listSplitCandidates(sourceContactId)).thenReturn(List.of(topic));
        when(items.listByTopicAndIdentity(topicId, identityId)).thenReturn(List.of(moved));
        when(items.countByTopic(topicId)).thenReturn(2);

        ContactGroupService service = new ContactGroupService(contacts, identities, null, topics, items,
                null, splitReconciler);

        service.split(identityId, "拆分联系人", UUID.randomUUID());

        verify(items).updateById(moved);
        verify(splitReconciler, never()).recomputeAfterSourceSplit(topicId);
    }

    private static ContactEntity sourceContact(UUID contactId) {
        ContactEntity contact = new ContactEntity();
        contact.setId(contactId);
        return contact;
    }
}
