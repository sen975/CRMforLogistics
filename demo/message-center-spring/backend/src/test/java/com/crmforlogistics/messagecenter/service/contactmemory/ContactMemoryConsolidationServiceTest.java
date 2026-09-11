package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.entity.ContactAiLabelEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ContactMemoryConsolidationServiceTest {

    private static final UUID CONTACT_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-11T04:00:00Z");

    @Test
    void sameSemanticObservationIsMergedAndOnlyStableEvidencePromotesFact() {
        UUID messageId = UUID.randomUUID();
        ContactMemoryModels.Context context = contextWithMessage(messageId);
        ContactMemoryModels.ObservationCandidate repeated = observation(
                messageId, "客户明确偏好海运", "prefers sea freight");
        ContactMemoryModels.LlmOutput output = output(List.of(repeated, repeated), null);

        ContactMemoryModels.ConsolidationResult result = new ContactMemoryConsolidationService()
                .consolidate(context, output);

        assertThat(result.observations()).hasSize(1);
        assertThat(result.facts()).isEmpty();

        ContactMemoryModels.ObservationCandidate independent = observation(
                UUID.randomUUID(), "客户再次确认偏好海运", "prefers sea freight");
        ContactMemoryModels.ConsolidationResult promoted = new ContactMemoryConsolidationService()
                .consolidate(contextWithMessages(messageId, independent.evidence().get(0).id()),
                        output(List.of(repeated, independent), null));

        assertThat(promoted.facts()).singleElement()
                .extracting(ContactMemoryModels.FactCandidate::status)
                .isEqualTo(ContactMemoryModels.FactStatus.ACTIVE);
    }

    @Test
    void aiLabelUsesCategoryColorAndNormalizesName() {
        ContactMemoryModels.LabelChange change = new ContactMemoryModels.LabelChange(
                ContactMemoryModels.LabelOperation.ADD,
                ContactMemoryModels.Category.PRODUCT_INTEREST,
                "  海运  ",
                new BigDecimal("0.90"),
                List.of(new ContactMemoryModels.EvidenceRef(
                        ContactMemoryModels.EvidenceType.MESSAGE, changeEvidenceId())),
                "明确询价");

        ContactMemoryModels.ConsolidationResult result = new ContactMemoryConsolidationService()
                .consolidate(contextWithMessage(changeEvidenceId()), output(List.of(), List.of(change)));

        ContactMemoryModels.LabelCandidate label = result.labels().get(0);
        assertThat(label.normalizedName()).isEqualTo("海运");
        assertThat(label.colorToken()).isEqualTo("green");
        assertThat(label.status()).isEqualTo(ContactMemoryModels.LabelStatus.ACTIVE);
    }

    @Test
    void existingActiveFactIsConfirmedByOneNewIndependentEvidence() {
        ContactMemoryFactEntity existingFact = new ContactMemoryFactEntity();
        existingFact.setCategory(ContactMemoryModels.Category.PRODUCT_INTEREST.name());
        existingFact.setNormalizedKey("product_interest");
        existingFact.setNormalizedValue("prefers sea freight");
        existingFact.setDisplayValue("prefers sea freight");
        existingFact.setPolarity(ContactMemoryModels.Polarity.POSITIVE.name());
        existingFact.setStatus(ContactMemoryModels.FactStatus.ACTIVE.name());
        existingFact.setEvidenceCount(2);

        ContactMemoryModels.Context context = new ContactMemoryModels.Context(
                CONTACT_ID, OWNER_ID, List.of(), null, List.of(), List.of(existingFact),
                List.of(), new ContactMemoryModels.StableContext(null, List.of(existingFact), List.of(), List.of()),
                List.of(), List.of(), null, "cursor");
        ContactMemoryModels.ObservationCandidate observation = observation(
                UUID.randomUUID(), "再次确认偏好海运", "prefers sea freight");
        context = contextWithMessage(observation.evidence().get(0).id(), List.of(existingFact));

        ContactMemoryModels.ConsolidationResult result = new ContactMemoryConsolidationService()
                .consolidate(context, output(List.of(observation), null));

        assertThat(result.facts()).singleElement()
                .extracting(ContactMemoryModels.FactCandidate::normalizedValue)
                .isEqualTo("prefers sea freight");
    }

    private static ContactMemoryModels.Context context() {
        return new ContactMemoryModels.Context(
                CONTACT_ID, OWNER_ID, List.of(), null, List.of(), List.of(), List.of(),
                new ContactMemoryModels.StableContext(null, List.of(), List.of(), List.of()),
                List.of(), List.of(), null, "2026-09-11T04:00:00Z|" + UUID.randomUUID());
    }

    private static ContactMemoryModels.Context contextWithMessage(UUID messageId) {
        return contextWithMessages(messageId);
    }

    private static ContactMemoryModels.Context contextWithMessage(
            UUID messageId, List<ContactMemoryFactEntity> facts) {
        com.crmforlogistics.messagecenter.entity.MessageEntity message =
                new com.crmforlogistics.messagecenter.entity.MessageEntity();
        message.setId(messageId);
        return new ContactMemoryModels.Context(
                CONTACT_ID, OWNER_ID, List.of(message), null, List.of(), facts, List.of(),
                new ContactMemoryModels.StableContext(null, facts, List.of(), List.of()),
                List.of(), List.of(), null, "cursor");
    }

    private static ContactMemoryModels.Context contextWithMessages(UUID... messageIds) {
        List<com.crmforlogistics.messagecenter.entity.MessageEntity> messages =
                java.util.Arrays.stream(messageIds).map(id -> {
                    com.crmforlogistics.messagecenter.entity.MessageEntity message =
                            new com.crmforlogistics.messagecenter.entity.MessageEntity();
                    message.setId(id);
                    return message;
                }).toList();
        return new ContactMemoryModels.Context(
                CONTACT_ID, OWNER_ID, messages, null, List.of(), List.of(), List.of(),
                new ContactMemoryModels.StableContext(null, List.of(), List.of(), List.of()),
                List.of(), List.of(), null, "cursor");
    }

    private static UUID changeEvidenceId() {
        return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    private static ContactMemoryModels.ObservationCandidate observation(
            UUID evidenceId, String reason, String value) {
        return new ContactMemoryModels.ObservationCandidate(
                ContactMemoryModels.Category.PRODUCT_INTEREST,
                "product_interest",
                value,
                ContactMemoryModels.Polarity.POSITIVE,
                new BigDecimal("0.80"),
                List.of(new ContactMemoryModels.EvidenceRef(
                        ContactMemoryModels.EvidenceType.MESSAGE, evidenceId)),
                reason);
    }

    private static ContactMemoryModels.LlmOutput output(
            List<ContactMemoryModels.ObservationCandidate> observations,
            List<ContactMemoryModels.LabelChange> changes) {
        return new ContactMemoryModels.LlmOutput(observations, null, changes, List.of(),
                "test-model", "{}");
    }
}
