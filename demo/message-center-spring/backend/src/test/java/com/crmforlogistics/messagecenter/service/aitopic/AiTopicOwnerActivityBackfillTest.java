package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.mapper.AiTopicOwnerActivityMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTopicOwnerActivityBackfillTest {
    @Test
    void recordsOnlyOneBoundedPageOfHistoricalOwners() {
        AiTopicOwnerActivityMapper mapper = mock(AiTopicOwnerActivityMapper.class);
        AiTopicOwnerActivityService activities = mock(AiTopicOwnerActivityService.class);
        UUID contactId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-01T08:00:00Z");
        when(mapper.listHistoricalCandidates(2)).thenReturn(List.of(
                new AiTopicOwnerActivityMapper.ActivityCandidate("CONTACT", contactId, at),
                new AiTopicOwnerActivityMapper.ActivityCandidate("WECOM_GROUP", groupId, at.plusSeconds(1))));

        int recorded = new AiTopicOwnerActivityBackfill(mapper, activities).runOnce(2);

        org.assertj.core.api.Assertions.assertThat(recorded).isEqualTo(2);
        verify(activities).recordActivity(AiTopicOwnerService.contact(contactId), at);
        verify(activities).recordActivity(AiTopicOwnerService.group(groupId), at.plusSeconds(1));
    }
}
