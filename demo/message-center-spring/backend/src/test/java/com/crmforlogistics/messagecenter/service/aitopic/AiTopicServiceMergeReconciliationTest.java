package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.entity.AiTopicGenerationJobEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicGenerationJobMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicGenerationAttemptMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicItemMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicVersionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTopicServiceMergeReconciliationTest {
    @Test
    void movesArchivedMergedSourceWhenAssignmentMatchesExistingTopic() {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        AiTopicMapper topics = mock(AiTopicMapper.class);
        AiTopicItemMapper items = mock(AiTopicItemMapper.class);
        AiTopicEntity existing = new AiTopicEntity();
        existing.setId(topicId);
        existing.setOwnerType("CONTACT");
        existing.setOwnerId(contactId);
        existing.setContactId(contactId);
        existing.setStatus("READY");
        existing.setTitle("报价");
        existing.setAiSummary("报价讨论");
        existing.setFirstOccurredAt(Instant.parse("2026-08-01T00:00:00Z"));
        existing.setLastOccurredAt(existing.getFirstOccurredAt());
        existing.setVersion(1L);
        when(topics.listReadyByOwner("CONTACT", contactId)).thenReturn(List.of(existing));
        when(topics.selectById(topicId)).thenReturn(existing);
        when(items.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(items.moveArchivedMergedSourceToTopic(topicId, contactId, "MESSAGE", sourceId)).thenReturn(1);

        AiTopicInputService input = mock(AiTopicInputService.class);
        when(input.collect(eq(AiTopicOwnerService.contact(contactId)), eq(userId), any()))
                .thenReturn(new AiTopicModels.InputBatch(List.of(new AiTopicModels.SourceItem(sourceId,
                        AiTopicModels.SourceType.MESSAGE, "email", Instant.parse("2026-08-02T00:00:00Z"),
                        "inbound", "报价", "确认报价")), "f".repeat(64), false));
        TopicAiGateway gateway = mock(TopicAiGateway.class);
        when(gateway.generate(any())).thenReturn(new AiTopicModels.GenerationOutput(List.of(
                new AiTopicModels.TopicAssignment(topicId.toString(), "报价", "报价讨论", .95, List.of(sourceId)))));
        AiTopicGenerationJobEntity job = new AiTopicGenerationJobEntity();
        job.setId(UUID.randomUUID());
        job.setOwnerType("CONTACT");
        job.setOwnerId(contactId);
        job.setContactId(contactId);
        job.setCreatedByUserId(userId);
        job.setInputFingerprint("f".repeat(64));
        job.setJobKind("INCREMENTAL");

        AiTopicService service = new AiTopicService(mock(com.crmforlogistics.messagecenter.service.contact.ContactService.class), input,
                topics, items, mock(AiTopicGenerationJobMapper.class), mock(AiTopicVersionMapper.class),
                new AiTopicConfigHolder(new AiTopicConfig("", "", "model", 30, 200, 262144, .65, 1, 3, 120, 30)),
                mock(ContactIdentityMapper.class), null, null, null, null, mock(AiTopicGenerationAttemptMapper.class));

        service.generate(job, userId, gateway);

        verify(items).moveArchivedMergedSourceToTopic(topicId, contactId, "MESSAGE", sourceId);
    }
}
