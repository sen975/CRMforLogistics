package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class WeComChatDataRetentionSummaryProtectionTest {
    @Test
    void retentionSkipsMessagesWithPendingSummary() {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomChatDataStoreMaxMessages()).thenReturn(1);
        when(config.wecomChatDataStoreMaxBytes()).thenReturn(100L);
        WeComChatDataMessageMapper mapper = mock(WeComChatDataMessageMapper.class);
        WeComViewerReferenceLeaseRegistry leases = mock(WeComViewerReferenceLeaseRegistry.class);
        WeComMessageSummaryRepository summaries = mock(WeComMessageSummaryRepository.class);
        UUID protectedId = UUID.randomUUID();
        UUID deletableId = UUID.randomUUID();
        when(leases.leasedMessageIds()).thenReturn(Set.of());
        when(mapper.retentionUsage()).thenReturn(
                new WeComChatDataMessageMapper.RetentionUsage(2, 200L),
                new WeComChatDataMessageMapper.RetentionUsage(1, 100L));
        when(mapper.oldestRetentionCandidates(anyInt())).thenReturn(List.of(
                new WeComChatDataMessageMapper.RetentionCandidate(protectedId, "protected", 100L),
                new WeComChatDataMessageMapper.RetentionCandidate(deletableId, "deletable", 100L)));
        when(summaries.countNonTerminalByMsgids(anyList())).thenReturn(Set.of("protected"));
        when(mapper.deleteRetentionCandidates(anyList())).thenReturn(1);

        WeComChatDataRetention retention = new WeComChatDataRetention(config, mapper, leases, summaries);
        WeComChatDataRetention.RetentionResult result = retention.enforce();

        verify(mapper).deleteRetentionCandidates(argThat(ids -> ids.equals(List.of(deletableId))));
        assertThat(result.deleted()).isEqualTo(1);
    }
}
