package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataMessageEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeComMessageSummaryBackfillTest {
    @Test
    void backfillCreatesOnlyMissingJobsWithinConfiguredBound() {
        AppConfig config = mock(AppConfig.class);
        when(config.localDevMode()).thenReturn(false);
        when(config.wecomSuiteId()).thenReturn("suite");
        when(config.wecomLoginAuthCorpId()).thenReturn("corp");
        when(config.wecomMessageSummaryBackfillBatchSize()).thenReturn(200);
        WeComInstallationService installations = mock(WeComInstallationService.class);
        WeComChatDataMessageMapper messages = mock(WeComChatDataMessageMapper.class);
        WeComMessageSummaryRepository repository = mock(WeComMessageSummaryRepository.class);
        UUID installationId = UUID.randomUUID();
        when(installations.resolveInstallation("suite", "corp"))
                .thenReturn(new ResolvedInstallation(installationId.toString(), "suite", "corp", "agent", "code", 1));
        WeComChatDataMessageEntity oldMessage = new WeComChatDataMessageEntity();
        oldMessage.setInstallationId(installationId);
        oldMessage.setMsgid("old");
        oldMessage.setSendTime(1L);
        when(messages.findForSummaryBackfill(installationId, 200)).thenReturn(List.of(oldMessage));
        when(repository.enqueueIfAbsent(any())).thenReturn(true);

        int result = new WeComMessageSummaryBackfill(config, installations, messages, repository)
                .runOnce(installationId, Instant.parse("2026-08-31T09:00:00Z"));

        assertThat(result).isEqualTo(1);
        verify(repository).enqueueIfAbsent(argThat(command -> command.msgid().equals("old")
                && command.rawRequestJson().contains("\"operation\":\"submit\"")));
    }
}
