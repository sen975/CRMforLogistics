package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.service.wecom.WeComMessageSummaryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WeComMessageSummaryControllerTest {
    @Test
    void readsSummaryDiagnosticsWithoutSecrets() throws Exception {
        AppConfig config = mock(AppConfig.class);
        when(config.wecomSuiteId()).thenReturn("suite");
        when(config.wecomLoginAuthCorpId()).thenReturn("corp");
        UUID installationId = UUID.randomUUID();
        WeComInstallationService installations = mock(WeComInstallationService.class);
        when(installations.resolveInstallation("suite", "corp"))
                .thenReturn(new ResolvedInstallation(installationId.toString(), "suite", "corp", "agent", "code", 1));
        WeComMessageSummaryRepository repository = mock(WeComMessageSummaryRepository.class);
        when(repository.findByMsgid(installationId, "m-1")).thenReturn(Optional.of(
                new WeComMessageSummaryRepository.JobView(true, UUID.randomUUID(), installationId, "corp", null,
                        "m-1", 100L, "FAILED", "job-1", null,
                        "{\"operation\":\"submit\",\"msgid\":\"m-1\"}", "{\"status\":1}",
                        "RESPONSE_DATA", "AI_RESPONSE_INVALID", "official result is missing a non-empty summary", "RETRY_EXHAUSTED", 20,
                        Instant.now(), Instant.now(), Instant.now(), Instant.now(), Instant.now())));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new WeComMessageSummaryController(config, installations, repository)).build();

        mvc.perform(get("/api/v1/wecom/message-summaries/m-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.validationStage").value("RESPONSE_DATA"))
                .andExpect(jsonPath("$.lastErrorDiagnostic").value("official result is missing a non-empty summary"))
                .andExpect(jsonPath("$.secretKey").doesNotExist())
                .andExpect(jsonPath("$.rawRequestJson").value(
                        "{\"operation\":\"submit\",\"msgid\":\"m-1\"}"));
    }
}
