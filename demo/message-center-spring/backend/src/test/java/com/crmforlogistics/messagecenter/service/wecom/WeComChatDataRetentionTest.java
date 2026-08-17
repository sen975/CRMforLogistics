package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComChatDataRetentionTest {

    @Test
    void deletesOldestUnleasedReferencesUntilBothBudgetsAreSatisfied() {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomChatDataStoreMaxMessages()).thenReturn(1);
        when(config.wecomChatDataStoreMaxBytes()).thenReturn(120L);
        WeComChatDataMessageMapper mapper = mock(WeComChatDataMessageMapper.class);
        WeComViewerReferenceLeaseRegistry leases = mock(WeComViewerReferenceLeaseRegistry.class);
        UUID leasedId = UUID.randomUUID();
        UUID firstDelete = UUID.randomUUID();
        UUID secondDelete = UUID.randomUUID();
        when(leases.leasedMessageIds()).thenReturn(java.util.Set.of("leased-msg"));
        when(mapper.retentionUsage()).thenReturn(
                new WeComChatDataMessageMapper.RetentionUsage(3, 330L),
                new WeComChatDataMessageMapper.RetentionUsage(1, 100L));
        when(mapper.oldestRetentionCandidates(201)).thenReturn(List.of(
                new WeComChatDataMessageMapper.RetentionCandidate(leasedId, "leased-msg", 100L),
                new WeComChatDataMessageMapper.RetentionCandidate(firstDelete, "old-1", 110L),
                new WeComChatDataMessageMapper.RetentionCandidate(secondDelete, "old-2", 120L)));
        when(mapper.deleteRetentionCandidates(org.mockito.ArgumentMatchers.anyList())).thenReturn(2);

        WeComChatDataRetention retention = new WeComChatDataRetention(config, mapper, leases);

        WeComChatDataRetention.RetentionResult result = retention.enforce();

        @SuppressWarnings("unchecked")
        var ids = (org.mockito.ArgumentCaptor<List<UUID>>) (Object) forClass(List.class);
        verify(mapper).deleteRetentionCandidates(ids.capture());
        assertThat(ids.getValue()).containsExactly(firstDelete, secondDelete);
        assertThat(result.deleted()).isEqualTo(2);
        assertThat(result.withinBudget()).isTrue();
    }
}
