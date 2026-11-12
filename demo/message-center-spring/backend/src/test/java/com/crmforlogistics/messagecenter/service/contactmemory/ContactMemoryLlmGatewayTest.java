package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContactMemoryLlmGatewayTest {

    @Test
    void parsesValidJsonWrappedInCodeFence() {
        UUID messageId = UUID.randomUUID();
        ContactMemoryModels.Context context = contextWithMessage(messageId);
        String json = """
                ```json
                {
                  "observations": [{
                    "category": "NEED",
                    "normalizedKey": "采购周期",
                    "observedValue": "本月确认",
                    "polarity": "POSITIVE",
                    "confidence": 0.9,
                    "evidence": [{"type": "MESSAGE", "id": "%s"}],
                    "reason": "客户明确说明"
                  }],
                  "profile": {"content": "客户本月会确认采购周期。"},
                  "labelChanges": [{
                    "operation": "ADD",
                    "category": "NEED",
                    "name": "本月采购",
                    "confidence": 0.9,
                    "evidence": [{"type": "MESSAGE", "id": "%s"}],
                    "reason": "客户明确说明"
                  }],
                  "noises": []
                }
                ```
                """.formatted(messageId, messageId);

        ContactMemoryModels.LlmOutput output =
                OpenAiCompatibleContactMemoryGateway.parseOutput(json, context);

        assertThat(output.observations()).hasSize(1);
        assertThat(output.profile().content()).isEqualTo("客户本月会确认采购周期。");
        assertThat(output.labelChanges()).singleElement()
                .extracting(ContactMemoryModels.LabelChange::operation)
                .isEqualTo(ContactMemoryModels.LabelOperation.ADD);
    }

    @Test
    void rejectsUnknownEnumValues() {
        UUID messageId = UUID.randomUUID();
        String json = validJson(messageId)
                .replace("\"category\": \"NEED\"", "\"category\": \"UNKNOWN\"");

        assertThatThrownBy(() -> OpenAiCompatibleContactMemoryGateway.parseOutput(
                json, contextWithMessage(messageId)))
                .isInstanceOf(ContactMemoryLlmGateway.GatewayException.class)
                .hasMessage("INVALID_OUTPUT");
    }

    @Test
    void rejectsEvidenceIdOutsideTheInputContext() {
        UUID allowed = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        String json = validJson(unknown);

        assertThatThrownBy(() -> OpenAiCompatibleContactMemoryGateway.parseOutput(
                json, contextWithMessage(allowed)))
                .isInstanceOf(ContactMemoryLlmGateway.GatewayException.class)
                .hasMessage("INVALID_EVIDENCE");
    }

    @Test
    void rejectsProfileLongerThanTwoHundredChineseCharacters() {
        UUID messageId = UUID.randomUUID();
        String json = validJson(messageId).replace(
                "客户本月会确认采购周期。",
                "客".repeat(201));

        assertThatThrownBy(() -> OpenAiCompatibleContactMemoryGateway.parseOutput(
                json, contextWithMessage(messageId)))
                .isInstanceOf(ContactMemoryLlmGateway.GatewayException.class)
                .hasMessage("INVALID_OUTPUT");
    }

    @Test
    void rejectsConfidenceOutsideZeroToOne() {
        UUID messageId = UUID.randomUUID();
        String json = validJson(messageId).replace("0.9", "1.1");

        assertThatThrownBy(() -> OpenAiCompatibleContactMemoryGateway.parseOutput(
                json, contextWithMessage(messageId)))
                .isInstanceOf(ContactMemoryLlmGateway.GatewayException.class)
                .hasMessage("INVALID_OUTPUT");
    }

    @Test
    void rejectsEvidenceFromAnotherContactEvenWhenItIsPresentInContext() {
        UUID messageId = UUID.randomUUID();
        UUID otherContactId = UUID.randomUUID();
        ContactMemoryFactEntity fact = new ContactMemoryFactEntity();
        fact.setId(UUID.randomUUID());
        fact.setContactId(otherContactId);
        fact.setOwnerUserId(UUID.randomUUID());
        fact.setCategory("NEED");
        fact.setNormalizedKey("采购周期");
        fact.setNormalizedValue("本月");
        fact.setStatus("ACTIVE");
        String json = """
                {
                  "observations": [],
                  "profile": null,
                  "labelChanges": [{
                    "operation": "ADD",
                    "category": "NEED",
                    "name": "本月采购",
                    "confidence": 0.9,
                    "evidence": [{"type": "LONG_TERM_FACT", "id": "%s"}],
                    "reason": "不应跨联系人引用"
                  }],
                  "noises": []
                }
                """.formatted(fact.getId());
        ContactMemoryModels.Context context = contextWithMessageAndFact(messageId, fact);

        assertThatThrownBy(() -> OpenAiCompatibleContactMemoryGateway.parseOutput(json, context))
                .isInstanceOf(ContactMemoryLlmGateway.GatewayException.class)
                .hasMessage("OWNER_MISMATCH");
    }

    @Test
    void rejectsMoreThanTwentyLabelChanges() {
        UUID messageId = UUID.randomUUID();
        String label = """
                {"operation":"ADD","category":"NEED","name":"标签%s","confidence":0.9,
                 "evidence":[{"type":"MESSAGE","id":"%s"}],"reason":"客户明确说明"}
                """;
        String changes = java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> label.formatted(index, messageId))
                .collect(java.util.stream.Collectors.joining(","));
        String json = """
                {"observations":[],"profile":null,"labelChanges":[%s],"noises":[]}
                """.formatted(changes);

        assertThatThrownBy(() -> OpenAiCompatibleContactMemoryGateway.parseOutput(json,
                contextWithMessage(messageId)))
                .isInstanceOf(ContactMemoryLlmGateway.GatewayException.class)
                .hasMessage("OUTPUT_LIMIT");
    }

    @Test
    void mapsTimeoutToStructuredRetryableCode() {
        ContactMemoryLlmGateway.GatewayException failure =
                OpenAiCompatibleContactMemoryGateway.transportFailure(new HttpTimeoutException("timeout"));

        assertThat(failure.code()).isEqualTo("LLM_TIMEOUT");
        assertThat(failure.retryable()).isTrue();
    }

    @Test
    void buildsRequestWhenMessageOptionalFieldsAreNull() {
        UUID messageId = UUID.randomUUID();
        ContactMemoryModels.Context context = contextWithMessage(messageId);

        assertThat(OpenAiCompatibleContactMemoryGateway.buildRequest(
                "test-model", context, new ObjectMapper(), 262_144))
                .isNotEmpty();
    }

    @Test
    void llmPayloadContainsManualTagsAsReadOnlyContext() throws Exception {
        UUID messageId = UUID.randomUUID();
        UUID tagId = UUID.randomUUID();
        ContactMemoryModels.Context context = contextWithMessageAndManualTag(messageId, tagId);

        String requestJson = new ObjectMapper().writeValueAsString(
                OpenAiCompatibleContactMemoryGateway.buildRequest(
                        "test-model", context, new ObjectMapper(), 262_144));

        assertThat(requestJson).contains("manualTags", "重要客户", "red");
        assertThat(requestJson).contains("Manual tags are read-only context", "Never modify");
    }

    @Test
    void reportsInputLimitWhenSerializedContextExceedsRequestBudget() {
        assertThatThrownBy(() -> OpenAiCompatibleContactMemoryGateway.buildRequest(
                "test-model", contextWithMessage(UUID.randomUUID()), new ObjectMapper(), 100))
                .isInstanceOf(ContactMemoryLlmGateway.GatewayException.class)
                .hasMessage("INPUT_LIMIT");
    }

    @Test
    void rejectsProviderResponseExceedingByteBudget() {
        assertThatThrownBy(() -> OpenAiCompatibleContactMemoryGateway.readResponseBody(
                new ByteArrayInputStream("12345".getBytes(java.nio.charset.StandardCharsets.UTF_8)), 4))
                .isInstanceOf(ContactMemoryLlmGateway.GatewayException.class)
                .hasMessage("OUTPUT_LIMIT");
    }

    @Test
    void rejectsSentenceLikeAiLabelName() {
        UUID messageId = UUID.randomUUID();
        String json = validJson(messageId).replace("本月采购", "客户希望本月确认采购周期");

        assertThatThrownBy(() -> OpenAiCompatibleContactMemoryGateway.parseOutput(
                json, contextWithMessage(messageId)))
                .isInstanceOf(ContactMemoryLlmGateway.GatewayException.class)
                .hasMessage("INVALID_OUTPUT");
    }

    private static String validJson(UUID messageId) {
        return """
                {
                  "observations": [{
                    "category": "NEED",
                    "normalizedKey": "采购周期",
                    "observedValue": "本月确认",
                    "polarity": "POSITIVE",
                    "confidence": 0.9,
                    "evidence": [{"type": "MESSAGE", "id": "%s"}],
                    "reason": "客户明确说明"
                  }],
                  "profile": {"content": "客户本月会确认采购周期。"},
                  "labelChanges": [{
                    "operation": "ADD",
                    "category": "NEED",
                    "name": "本月采购",
                    "confidence": 0.9,
                    "evidence": [{"type": "MESSAGE", "id": "%s"}],
                    "reason": "客户明确说明"
                  }],
                  "noises": []
                }
                """.formatted(messageId, messageId);
    }

    private static ContactMemoryModels.Context contextWithMessage(UUID messageId) {
        return contextWithMessageAndFact(messageId, null);
    }

    private static ContactMemoryModels.Context contextWithMessageAndFact(
            UUID messageId, ContactMemoryFactEntity fact) {
        UUID contactId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        MessageEntity message = new MessageEntity();
        message.setId(messageId);
        message.setDirection("inbound");
        message.setOccurredAt(Instant.parse("2026-09-11T02:00:00Z"));
        message.setBodyText("客户本月会确认采购周期");
        return new ContactMemoryModels.Context(
                contactId,
                ownerUserId,
                List.of(message),
                null,
                List.of(),
                fact == null ? List.of() : List.of(fact),
                List.of(),
                new ContactMemoryModels.StableContext(null,
                        fact == null ? List.of() : List.of(fact), List.of(), List.of()),
                List.of(),
                List.of(),
                null,
                "2026-09-11T02:00:00Z|" + messageId);
    }

    private static ContactMemoryModels.Context contextWithMessageAndManualTag(
            UUID messageId, UUID tagId) {
        ContactMemoryModels.Context context = contextWithMessage(messageId);
        return new ContactMemoryModels.Context(
                context.contactId(), context.ownerUserId(), context.inboundMessages(),
                List.of(new ContactMemoryModels.ManualTag(tagId, "重要客户", "red")),
                context.currentProfile(), context.observations(), context.activeFacts(),
                context.activeLabels(), context.stableContext(), context.topics(),
                context.callTranscripts(), context.inputCursor(), context.outputCursor());
    }
}
