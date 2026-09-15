package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.entity.ContactAiLabelEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEntity;
import com.crmforlogistics.messagecenter.config.ContactMemoryConfig;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;
import org.mockito.ArgumentCaptor;

class ContactMemoryMutationServiceTest {

    private static final UUID CONTACT_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID STATE_ID = UUID.randomUUID();
    private static final UUID LEASE_TOKEN = UUID.randomUUID();
    private static final String LEASE_OWNER = "memory-worker-1";

    @Test
    void profileEvidenceCannotPretendToBeLongTermFactEvidence() {
        ContactMemoryMapper memory = mock(ContactMemoryMapper.class);
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryModels.FactCandidate fact = new ContactMemoryModels.FactCandidate(
                ContactMemoryModels.Category.IDENTITY,
                "role",
                "buyer",
                "采购负责人",
                ContactMemoryModels.Polarity.POSITIVE,
                ContactMemoryModels.FactStatus.ACTIVE,
                new BigDecimal("0.90"),
                List.of(new ContactMemoryModels.EvidenceRef(
                        ContactMemoryModels.EvidenceType.PROFILE_VERSION, UUID.randomUUID())));
        ContactMemoryModels.ConsolidationResult result = new ContactMemoryModels.ConsolidationResult(
                UUID.randomUUID(), null, "cursor", List.of(), List.of(fact), List.of(), null, "model");

        assertThatThrownBy(() -> new ContactMemoryMutationService(memory, states, mock(ContactMemoryAttemptService.class))
                .persist(OWNER_ID, CONTACT_ID, lease(), result, attempt(result)))
                .isInstanceOf(ContactMemoryModels.ValidationException.class)
                .hasMessageContaining("INVALID_EVIDENCE");
        verify(memory, never()).insertFact(any(), eq(CONTACT_ID), eq(OWNER_ID));
        verify(states, never()).complete(any(), any(), any(), any(), any());
    }

    @Test
    void labelMatchesSupportingFactByNormalizedValue() {
        ContactMemoryMapper memory = mock(ContactMemoryMapper.class);
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        when(memory.insertFact(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);
        when(memory.findFact(any(), eq(CONTACT_ID), eq("PRODUCT_INTEREST"),
                eq("product_interest"), eq("sea freight"), eq("POSITIVE")))
                .thenAnswer(invocation -> {
                    ContactMemoryFactEntity entity = new ContactMemoryFactEntity();
                    entity.setId(UUID.randomUUID());
                    return entity;
                });
        when(memory.insertFactEvidence(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);
        when(memory.countFactEvidence(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1L);
        when(memory.updateFact(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);
        when(memory.insertAiLabel(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);
        when(memory.insertAiLabelEvidence(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);
        when(memory.updateAiLabel(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);
        when(states.complete(any(), any(), any(), any(), any())).thenReturn(1);

        UUID evidenceId = UUID.randomUUID();
        ContactMemoryModels.FactCandidate fact = new ContactMemoryModels.FactCandidate(
                ContactMemoryModels.Category.PRODUCT_INTEREST,
                "product_interest",
                "sea freight",
                "海运",
                ContactMemoryModels.Polarity.POSITIVE,
                ContactMemoryModels.FactStatus.ACTIVE,
                new BigDecimal("0.90"),
                List.of(new ContactMemoryModels.EvidenceRef(
                        ContactMemoryModels.EvidenceType.MESSAGE, evidenceId)));
        ContactMemoryModels.LabelCandidate label = new ContactMemoryModels.LabelCandidate(
                ContactMemoryModels.LabelOperation.ADD,
                ContactMemoryModels.Category.PRODUCT_INTEREST,
                "sea freight",
                "海运",
                "green",
                ContactMemoryModels.LabelStatus.ACTIVE,
                new BigDecimal("0.90"),
                List.of(new ContactMemoryModels.EvidenceRef(
                        ContactMemoryModels.EvidenceType.MESSAGE, evidenceId)),
                "明确偏好");
        ContactMemoryModels.ConsolidationResult result = new ContactMemoryModels.ConsolidationResult(
                UUID.randomUUID(), null, "cursor", List.of(), List.of(fact), List.of(label), null, "model");

        assertThatCode(() -> new ContactMemoryMutationService(memory, states, mock(ContactMemoryAttemptService.class))
                .persist(OWNER_ID, CONTACT_ID, lease(), result, attempt(result)))
                .doesNotThrowAnyException();
        verify(states).complete(eq(STATE_ID), eq(LEASE_TOKEN), eq("cursor"), eq((UUID) null), any());
    }

    @Test
    void successfulMutationStoresRealInputAndEvidenceCountsAndUpdatesProfilePointer() {
        ContactMemoryMapper memory = mock(ContactMemoryMapper.class);
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryAttemptService attempts = mock(ContactMemoryAttemptService.class);
        UUID profileId = UUID.randomUUID();
        ContactMemoryModels.AttemptRun run = new ContactMemoryModels.AttemptRun(
                UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        when(memory.nextProfileVersion(eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(2L);
        when(memory.insertProfile(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenAnswer(invocation -> {
            ((com.crmforlogistics.messagecenter.entity.ContactProfileVersionEntity) invocation.getArgument(0))
                    .setId(profileId);
            return 1;
        });
        when(states.complete(any(), any(), any(), any(), any())).thenReturn(1);

        ContactMemoryModels.ConsolidationResult result = new ContactMemoryModels.ConsolidationResult(
                run.generationBatchId(), "input", "output", List.of(), List.of(), List.of(),
                new ContactMemoryModels.ProfileCandidate("新画像"), "model", 3, 4, "NEW_CONTEXT");

        new ContactMemoryMutationService(memory, states, attempts)
                .persist(OWNER_ID, CONTACT_ID, lease(), result, run);

        ArgumentCaptor<com.crmforlogistics.messagecenter.entity.ContactProfileVersionEntity> profile =
                ArgumentCaptor.forClass(com.crmforlogistics.messagecenter.entity.ContactProfileVersionEntity.class);
        verify(memory).insertProfile(profile.capture(), eq(CONTACT_ID), eq(OWNER_ID));
        assertThat(profile.getValue().getInputMessageCount()).isEqualTo(3);
        assertThat(profile.getValue().getEvidenceCount()).isEqualTo(4);
        verify(states).complete(eq(STATE_ID), eq(LEASE_TOKEN), eq("output"), eq(profileId), any());
        verify(attempts).succeed(eq(run), eq(result), eq(profileId), any());
    }

    @Test
    void conflictingActiveFactIsMarkedConflictedAfterNewFactPersists() {
        ContactMemoryMapper memory = mock(ContactMemoryMapper.class);
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        when(memory.insertFact(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);
        when(memory.insertFactEvidence(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);
        when(memory.countFactEvidence(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(2L);
        when(memory.updateFact(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);
        when(memory.findOppositeActiveFact(any(), eq(CONTACT_ID), eq("PRODUCT_INTEREST"),
                eq("product_interest"), eq("sea freight"), eq("POSITIVE")))
                .thenReturn(oppositeFact());
        when(states.complete(any(), any(), any(), any(), any())).thenReturn(1);

        ContactMemoryModels.FactCandidate fact = fact(
                ContactMemoryModels.Polarity.POSITIVE,
                UUID.randomUUID());
        ContactMemoryModels.ConsolidationResult result = new ContactMemoryModels.ConsolidationResult(
                UUID.randomUUID(), null, "cursor", List.of(), List.of(fact), List.of(), null, "model");

        new ContactMemoryMutationService(memory, states, mock(ContactMemoryAttemptService.class)).persist(
                OWNER_ID, CONTACT_ID, lease(), result, attempt(result));

        ArgumentCaptor<ContactMemoryFactEntity> captor =
                ArgumentCaptor.forClass(ContactMemoryFactEntity.class);
        verify(memory, times(2)).updateFact(captor.capture(), eq(CONTACT_ID), eq(OWNER_ID));
        assertThat(captor.getAllValues().get(1).getStatus())
                .isEqualTo(ContactMemoryModels.FactStatus.CONFLICTED.name());
    }

    @Test
    void restoreUpdatesExistingAiLabelWithoutCreatingDuplicate() {
        ContactMemoryMapper memory = mock(ContactMemoryMapper.class);
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactAiLabelEntity existing = new ContactAiLabelEntity();
        existing.setId(UUID.randomUUID());
        existing.setStatus(ContactMemoryModels.LabelStatus.STALE.name());
        ContactMemoryFactEntity supportingFact = new ContactMemoryFactEntity();
        supportingFact.setId(UUID.randomUUID());
        when(memory.findAiLabel(any(), eq(CONTACT_ID), eq("PRODUCT_INTEREST"), eq("sea freight")))
                .thenReturn(existing);
        when(memory.findActiveFactForLabel(any(), eq(CONTACT_ID), eq("PRODUCT_INTEREST"), eq("sea freight")))
                .thenReturn(supportingFact);
        when(memory.insertAiLabelEvidence(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);
        when(memory.updateAiLabel(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);
        when(states.complete(any(), any(), any(), any(), any())).thenReturn(1);

        ContactMemoryModels.LabelCandidate label = new ContactMemoryModels.LabelCandidate(
                ContactMemoryModels.LabelOperation.RESTORE,
                ContactMemoryModels.Category.PRODUCT_INTEREST,
                "sea freight",
                "海运",
                "green",
                ContactMemoryModels.LabelStatus.ACTIVE,
                new BigDecimal("0.90"),
                List.of(new ContactMemoryModels.EvidenceRef(
                        ContactMemoryModels.EvidenceType.MESSAGE, UUID.randomUUID())),
                "再次确认");
        ContactMemoryModels.ConsolidationResult result = new ContactMemoryModels.ConsolidationResult(
                UUID.randomUUID(), null, "cursor", List.of(), List.of(), List.of(label), null, "model");

        new ContactMemoryMutationService(memory, states, mock(ContactMemoryAttemptService.class)).persist(
                OWNER_ID, CONTACT_ID, lease(), result, attempt(result));

        verify(memory, never()).insertAiLabel(any(), eq(CONTACT_ID), eq(OWNER_ID));
        ArgumentCaptor<ContactAiLabelEntity> captor =
                ArgumentCaptor.forClass(ContactAiLabelEntity.class);
        verify(memory).updateAiLabel(captor.capture(), eq(CONTACT_ID), eq(OWNER_ID));
        assertThat(captor.getValue().getId()).isEqualTo(existing.getId());
        assertThat(captor.getValue().getStatus())
                .isEqualTo(ContactMemoryModels.LabelStatus.ACTIVE.name());
    }

    @Test
    void promotedObservationPointsToTheCreatedFact() {
        ContactMemoryMapper memory = mock(ContactMemoryMapper.class);
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        UUID observationId = UUID.randomUUID();
        UUID factId = UUID.randomUUID();
        when(memory.upsertObservation(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(observationId);
        when(memory.updateObservation(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);
        when(memory.insertObservationEvidence(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);
        when(memory.insertFact(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenAnswer(invocation -> {
            ((ContactMemoryFactEntity) invocation.getArgument(0)).setId(factId);
            return 1;
        });
        when(memory.insertFactEvidence(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);
        when(memory.countFactEvidence(eq(factId), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(2L);
        when(memory.updateFact(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);
        when(memory.findFact(any(), eq(CONTACT_ID), eq("PRODUCT_INTEREST"),
                eq("product_interest"), eq("sea freight"), eq("POSITIVE")))
                .thenAnswer(invocation -> factEntity(factId));
        when(memory.findCandidateObservation(eq(OWNER_ID), eq(CONTACT_ID),
                eq("PRODUCT_INTEREST"), eq("product_interest"), eq("sea freight"), eq("POSITIVE")))
                .thenAnswer(invocation -> observationEntity(observationId));
        when(memory.copyObservationEvidenceToFact(eq(observationId), eq(factId),
                eq(CONTACT_ID), eq(OWNER_ID), any())).thenReturn(1);
        when(memory.updateObservationLifecycle(eq(observationId), eq(CONTACT_ID), eq(OWNER_ID),
                eq(ContactMemoryModels.ObservationStatus.PROMOTED.name()), eq(factId))).thenReturn(1);
        when(states.complete(any(), any(), any(), any(), any())).thenReturn(1);

        ContactMemoryModels.EvidenceRef evidence = new ContactMemoryModels.EvidenceRef(
                ContactMemoryModels.EvidenceType.MESSAGE, UUID.randomUUID());
        ContactMemoryModels.ObservationCandidate observation = new ContactMemoryModels.ObservationCandidate(
                ContactMemoryModels.Category.PRODUCT_INTEREST, "product_interest", "sea freight",
                ContactMemoryModels.Polarity.POSITIVE, new BigDecimal("0.90"), List.of(evidence), "确认");
        ContactMemoryModels.FactCandidate fact = new ContactMemoryModels.FactCandidate(
                ContactMemoryModels.Category.PRODUCT_INTEREST, "product_interest", "sea freight", "海运",
                ContactMemoryModels.Polarity.POSITIVE, ContactMemoryModels.FactStatus.ACTIVE,
                new BigDecimal("0.90"), List.of(evidence));
        ContactMemoryModels.ConsolidationResult result = new ContactMemoryModels.ConsolidationResult(
                UUID.randomUUID(), null, "cursor", List.of(observation), List.of(fact), List.of(), null, "model");

        new ContactMemoryMutationService(memory, states, mock(ContactMemoryAttemptService.class)).persist(
                OWNER_ID, CONTACT_ID, lease(), result, attempt(result));

        var order = inOrder(memory);
        order.verify(memory).copyObservationEvidenceToFact(eq(observationId), eq(factId),
                eq(CONTACT_ID), eq(OWNER_ID), any());
        order.verify(memory).updateObservationLifecycle(eq(observationId), eq(CONTACT_ID), eq(OWNER_ID),
                eq(ContactMemoryModels.ObservationStatus.PROMOTED.name()), eq(factId));
    }

    @Test
    void expiredCandidateObservationsAreExpiredBeforeNewResultsPersist() {
        ContactMemoryMapper memory = mock(ContactMemoryMapper.class);
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        when(memory.expireObservations(eq(CONTACT_ID), eq(OWNER_ID), any())).thenReturn(1);
        when(states.complete(any(), any(), any(), any(), any())).thenReturn(1);

        ContactMemoryModels.ConsolidationResult result = new ContactMemoryModels.ConsolidationResult(
                UUID.randomUUID(), null, "cursor", List.of(), List.of(), List.of(), null, "model");

        new ContactMemoryMutationService(memory, states, mock(ContactMemoryAttemptService.class)).persist(
                OWNER_ID, CONTACT_ID, lease(), result, attempt(result));

        verify(memory).expireObservations(eq(CONTACT_ID), eq(OWNER_ID), any());
    }

    @Test
    void observationExpiryUsesConfiguredTtl() {
        ContactMemoryMapper memory = mock(ContactMemoryMapper.class);
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        UUID observationId = UUID.randomUUID();
        when(memory.upsertObservation(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(observationId);
        when(memory.updateObservation(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);
        when(memory.insertObservationEvidence(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);
        when(states.complete(any(), any(), any(), any(), any())).thenReturn(1);

        ContactMemoryModels.EvidenceRef evidence = new ContactMemoryModels.EvidenceRef(
                ContactMemoryModels.EvidenceType.MESSAGE, UUID.randomUUID());
        ContactMemoryModels.ObservationCandidate observation = new ContactMemoryModels.ObservationCandidate(
                ContactMemoryModels.Category.NEED, "need", "stable sailing schedule",
                ContactMemoryModels.Polarity.POSITIVE, new BigDecimal("0.80"), List.of(evidence), "确认");
        ContactMemoryModels.ConsolidationResult result = new ContactMemoryModels.ConsolidationResult(
                UUID.randomUUID(), null, "cursor", List.of(observation), List.of(), List.of(), null, "model");
        ContactMemoryConfig config = new ContactMemoryConfig(
                50, 4000, 50000, 20, 1000, 10, 4000,
                100, 100, 100, 500, 2);
        Instant before = Instant.now();

        new ContactMemoryMutationService(memory, states, mock(ContactMemoryAttemptService.class), config).persist(
                OWNER_ID, CONTACT_ID, lease(), result, attempt(result));

        ArgumentCaptor<ContactMemoryObservationEntity> captor =
                ArgumentCaptor.forClass(ContactMemoryObservationEntity.class);
        verify(memory).upsertObservation(captor.capture(), eq(CONTACT_ID), eq(OWNER_ID));
        assertThat(captor.getValue().getExpiresAt())
                .isBetween(before.plus(2, java.time.temporal.ChronoUnit.DAYS).minusSeconds(1),
                        Instant.now().plus(2, java.time.temporal.ChronoUnit.DAYS).plusSeconds(1));
    }

    private static ContactMemoryFactEntity oppositeFact() {
        ContactMemoryFactEntity entity = new ContactMemoryFactEntity();
        entity.setId(UUID.randomUUID());
        entity.setCategory(ContactMemoryModels.Category.PRODUCT_INTEREST.name());
        entity.setNormalizedKey("product_interest");
        entity.setNormalizedValue("sea freight");
        entity.setPolarity(ContactMemoryModels.Polarity.NEGATIVE.name());
        entity.setStatus(ContactMemoryModels.FactStatus.ACTIVE.name());
        return entity;
    }

    private static ContactMemoryFactEntity factEntity(UUID id) {
        ContactMemoryFactEntity entity = new ContactMemoryFactEntity();
        entity.setId(id);
        return entity;
    }

    private static ContactMemoryObservationEntity observationEntity(UUID id) {
        ContactMemoryObservationEntity entity = new ContactMemoryObservationEntity();
        entity.setId(id);
        return entity;
    }

    private static ContactMemoryModels.FactCandidate fact(
            ContactMemoryModels.Polarity polarity, UUID evidenceId) {
        return new ContactMemoryModels.FactCandidate(
                ContactMemoryModels.Category.PRODUCT_INTEREST,
                "product_interest",
                "sea freight",
                "海运",
                polarity,
                ContactMemoryModels.FactStatus.ACTIVE,
                new BigDecimal("0.90"),
                List.of(new ContactMemoryModels.EvidenceRef(
                        ContactMemoryModels.EvidenceType.MESSAGE, evidenceId)));
    }

    private static ContactMemoryModels.Lease lease() {
        return new ContactMemoryModels.Lease(
                STATE_ID, CONTACT_ID, OWNER_ID, LEASE_OWNER, LEASE_TOKEN,
                Instant.now().plusSeconds(60));
    }

    private static ContactMemoryModels.AttemptRun attempt(
            ContactMemoryModels.ConsolidationResult result) {
        return new ContactMemoryModels.AttemptRun(
                UUID.randomUUID(), result.generationBatchId(), Instant.now());
    }
}
