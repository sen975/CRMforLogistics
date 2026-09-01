package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import com.crmforlogistics.messagecenter.entity.AiTopicGenerationAttemptEntity;
import com.crmforlogistics.messagecenter.entity.AiTopicGenerationJobEntity;
import com.crmforlogistics.messagecenter.mapper.AiTopicGenerationAttemptMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiTopicGenerationAuditServiceTest {
    @Test
    void storesBoundedRequestSnapshotWithoutAuthenticationHeaders() {
        AiTopicGenerationAttemptMapper mapper = mock(AiTopicGenerationAttemptMapper.class);
        AiTopicGenerationAuditService service = new AiTopicGenerationAuditService(mapper, new ObjectMapper());
        AiTopicGenerationJobEntity job = new AiTopicGenerationJobEntity();
        job.setId(UUID.randomUUID()); job.setContactId(UUID.randomUUID()); job.setOwnerType("CONTACT");
        job.setOwnerId(job.getContactId()); job.setAttemptCount(0);
        AiTopicModels.GenerationInput input = new AiTopicModels.GenerationInput(new AiTopicOwnerService.OwnerRef("CONTACT", job.getContactId()), List.of(
                new AiTopicModels.SourceItem(UUID.randomUUID(), AiTopicModels.SourceType.MESSAGE, "email", Instant.now(), "inbound", "subject", "message body")), List.of(), false);
        AiTopicConfig config = new AiTopicConfig("https://provider.example/v1?key=should-not-store", "secret-key", "model", 30, 200, 262144, .65, 1, 3, 120, 30, 256, 256);

        service.begin(job, input, config);

        var captured = org.mockito.ArgumentCaptor.forClass(AiTopicGenerationAttemptEntity.class);
        verify(mapper).insertStarted(captured.capture());
        assertThat(captured.getValue().getProviderHost()).isEqualTo("provider.example");
        assertThat(captured.getValue().getRequestPayload()).doesNotContain("secret-key").doesNotContain("Authorization");
        assertThat(captured.getValue().getRequestPayload()).isNotBlank();
        assertThat(captured.getValue().getOwnerType()).isEqualTo("CONTACT");
        assertThat(captured.getValue().getOwnerId()).isEqualTo(job.getContactId());
    }

    @Test
    void retainsValidationEvidenceWhileRedactingSensitiveProviderFields() {
        AiTopicGenerationAttemptMapper mapper = mock(AiTopicGenerationAttemptMapper.class);
        AiTopicGenerationAuditService service = new AiTopicGenerationAuditService(mapper, new ObjectMapper());
        AiTopicGenerationJobEntity job = new AiTopicGenerationJobEntity();
        job.setId(UUID.randomUUID()); job.setContactId(UUID.randomUUID()); job.setOwnerType("CONTACT");
        job.setOwnerId(job.getContactId()); job.setAttemptCount(0);
        AiTopicModels.GenerationInput input = new AiTopicModels.GenerationInput(new AiTopicOwnerService.OwnerRef("CONTACT", job.getContactId()),
                List.of(new AiTopicModels.SourceItem(UUID.randomUUID(), AiTopicModels.SourceType.MESSAGE, "email", Instant.now(), "inbound", "", "正文")),
                List.of(), false);
        AiTopicConfig config = new AiTopicConfig("https://provider.example", "api-key", "model", 30, 200, 262144, .65, 1, 3, 120, 30, 256, 256);
        AiTopicGenerationAuditService.Context context = service.begin(job, input, config);

        service.outcome(context, "RESPONSE_VALIDATE", "SUCCEEDED", 200,
                "{\"authorization\":\"Bearer top-secret\"}",
                "{\"api_key\":\"provider-secret\",\"choices\":[{\"message\":\"valid\"}]}",
                "{\"token\":\"parsed-secret\",\"topics\":[]}", null, null, config);

        var captured = org.mockito.ArgumentCaptor.forClass(AiTopicGenerationAttemptEntity.class);
        verify(mapper).updateOutcome(captured.capture());
        AiTopicGenerationAttemptEntity outcome = captured.getValue();
        assertThat(outcome.getStage()).isEqualTo("RESPONSE_VALIDATE");
        assertThat(outcome.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(outcome.getRawResponseBody()).contains("choices").doesNotContain("provider-secret");
        assertThat(outcome.getParsedResponse()).contains("topics").doesNotContain("parsed-secret");
        assertThat(outcome.getResponseHeaders()).doesNotContain("top-secret");
    }
}
