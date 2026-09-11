package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.entity.ContactAiLabelEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity;
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
import org.mockito.ArgumentCaptor;

class ContactMemoryMutationServiceTest {

    private static final UUID CONTACT_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID STATE_ID = UUID.randomUUID();
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

        assertThatThrownBy(() -> new ContactMemoryMutationService(memory, states)
                .persist(OWNER_ID, CONTACT_ID, lease(), result))
                .isInstanceOf(ContactMemoryModels.ValidationException.class)
                .hasMessageContaining("INVALID_EVIDENCE");
        verify(memory, never()).insertFact(any(), eq(CONTACT_ID), eq(OWNER_ID));
        verify(states, never()).complete(any(), any(), any(), any());
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
        when(states.complete(any(), any(), any(), any())).thenReturn(1);

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

        assertThatCode(() -> new ContactMemoryMutationService(memory, states)
                .persist(OWNER_ID, CONTACT_ID, lease(), result))
                .doesNotThrowAnyException();
        verify(states).complete(eq(STATE_ID), eq(LEASE_OWNER), eq("cursor"), any());
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
        when(states.complete(any(), any(), any(), any())).thenReturn(1);

        ContactMemoryModels.FactCandidate fact = fact(
                ContactMemoryModels.Polarity.POSITIVE,
                UUID.randomUUID());
        ContactMemoryModels.ConsolidationResult result = new ContactMemoryModels.ConsolidationResult(
                UUID.randomUUID(), null, "cursor", List.of(), List.of(fact), List.of(), null, "model");

        new ContactMemoryMutationService(memory, states).persist(
                OWNER_ID, CONTACT_ID, lease(), result);

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
        when(states.complete(any(), any(), any(), any())).thenReturn(1);

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

        new ContactMemoryMutationService(memory, states).persist(
                OWNER_ID, CONTACT_ID, lease(), result);

        verify(memory, never()).insertAiLabel(any(), eq(CONTACT_ID), eq(OWNER_ID));
        ArgumentCaptor<ContactAiLabelEntity> captor =
                ArgumentCaptor.forClass(ContactAiLabelEntity.class);
        verify(memory).updateAiLabel(captor.capture(), eq(CONTACT_ID), eq(OWNER_ID));
        assertThat(captor.getValue().getId()).isEqualTo(existing.getId());
        assertThat(captor.getValue().getStatus())
                .isEqualTo(ContactMemoryModels.LabelStatus.ACTIVE.name());
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
                STATE_ID, CONTACT_ID, OWNER_ID, LEASE_OWNER,
                Instant.now().plusSeconds(60));
    }
}
