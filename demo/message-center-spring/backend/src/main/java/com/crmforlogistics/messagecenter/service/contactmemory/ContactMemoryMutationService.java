package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.entity.ContactAiLabelEntity;
import com.crmforlogistics.messagecenter.entity.ContactAiLabelEvidenceEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryAttemptEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryFactEvidenceEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEvidenceEntity;
import com.crmforlogistics.messagecenter.entity.ContactProfileVersionEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ContactMemoryMutationService {
    private static final int MAX_LABEL_CHANGES = 20;
    private final ContactMemoryMapper memory;
    private final ContactMemoryStateMapper states;

    public ContactMemoryMutationService(ContactMemoryMapper memory, ContactMemoryStateMapper states) {
        this.memory = memory;
        this.states = states;
    }

    @Transactional
    public ContactMemoryModels.MutationResult persist(
            UUID ownerUserId,
            UUID contactId,
            ContactMemoryModels.Lease lease,
            ContactMemoryModels.ConsolidationResult result) {
        validateRequest(ownerUserId, contactId, lease, result);
        validateCandidates(result);

        int observationCount = persistObservations(ownerUserId, contactId, result);
        int factCount = persistFacts(ownerUserId, contactId, result);
        int labelCount = persistLabels(ownerUserId, contactId, result);
        UUID profileId = persistProfile(ownerUserId, contactId, result);
        persistAttempt(ownerUserId, contactId, result, profileId != null);

        if (states.complete(lease.stateId(), lease.leaseOwner(), result.outputCursor(), Instant.now()) != 1) {
            throw new ContactMemoryModels.ValidationException("LEASE_LOST");
        }
        return new ContactMemoryModels.MutationResult(
                profileId, result.outputCursor(), observationCount, factCount, labelCount);
    }

    private int persistObservations(UUID ownerUserId, UUID contactId,
                                    ContactMemoryModels.ConsolidationResult result) {
        int count = 0;
        Instant now = Instant.now();
        for (ContactMemoryModels.ObservationCandidate candidate : result.observations()) {
            ContactMemoryObservationEntity entity = new ContactMemoryObservationEntity();
            entity.setId(UUID.randomUUID());
            entity.setCategory(candidate.category().name());
            entity.setNormalizedKey(candidate.normalizedKey());
            entity.setObservedValue(candidate.observedValue());
            entity.setPolarity(candidate.polarity().name());
            entity.setConfidence(candidate.confidence());
            entity.setStatus(ContactMemoryModels.ObservationStatus.CANDIDATE.name());
            entity.setSourceCursor(result.outputCursor());
            entity.setGenerationBatchId(result.generationBatchId());
            entity.setObservedAt(now);
            entity.setExpiresAt(now.plusSeconds(30L * 24 * 60 * 60));
            memory.insertObservation(entity, contactId, ownerUserId);
            for (ContactMemoryModels.EvidenceRef evidence : candidate.evidence()) {
                ContactMemoryObservationEvidenceEntity evidenceEntity = new ContactMemoryObservationEvidenceEntity();
                evidenceEntity.setObservationId(entity.getId());
                evidenceEntity.setEvidenceType(evidence.type().name());
                evidenceEntity.setEvidenceId(evidence.id());
                evidenceEntity.setEvidenceExcerpt("");
                evidenceEntity.setGenerationBatchId(result.generationBatchId());
                memory.insertObservationEvidence(evidenceEntity, contactId, ownerUserId);
            }
            count++;
        }
        return count;
    }

    private int persistFacts(UUID ownerUserId, UUID contactId,
                             ContactMemoryModels.ConsolidationResult result) {
        int count = 0;
        Instant now = Instant.now();
        for (ContactMemoryModels.FactCandidate candidate : result.facts()) {
            ContactMemoryFactEntity entity = new ContactMemoryFactEntity();
            entity.setId(UUID.randomUUID());
            entity.setCategory(candidate.category().name());
            entity.setNormalizedKey(candidate.normalizedKey());
            entity.setNormalizedValue(candidate.normalizedValue());
            entity.setDisplayValue(candidate.displayValue());
            entity.setPolarity(candidate.polarity().name());
            entity.setStatus(candidate.status().name());
            entity.setConfidence(candidate.confidence());
            entity.setEvidenceCount(candidate.evidence().size());
            entity.setFirstSeenAt(now);
            entity.setLastSeenAt(now);
            entity.setLastConfirmedAt(now);
            entity.setGenerationBatchId(result.generationBatchId());
            int inserted = memory.insertFact(entity, contactId, ownerUserId);
            ContactMemoryFactEntity stored = inserted == 1 ? entity : memory.findFact(
                    ownerUserId, contactId, entity.getCategory(), entity.getNormalizedKey(),
                    entity.getNormalizedValue(), entity.getPolarity());
            if (stored == null || stored.getId() == null) {
                throw new ContactMemoryModels.ValidationException("PERSISTENCE_FAILED");
            }
            for (ContactMemoryModels.EvidenceRef evidence : candidate.evidence()) {
                ContactMemoryFactEvidenceEntity evidenceEntity = new ContactMemoryFactEvidenceEntity();
                evidenceEntity.setFactId(stored.getId());
                evidenceEntity.setEvidenceType(evidence.type().name());
                evidenceEntity.setEvidenceId(evidence.id());
                evidenceEntity.setEvidenceExcerpt("");
                evidenceEntity.setGenerationBatchId(result.generationBatchId());
                memory.insertFactEvidence(evidenceEntity, contactId, ownerUserId);
            }
            entity.setId(stored.getId());
            entity.setEvidenceCount((int) memory.countFactEvidence(stored.getId(), contactId, ownerUserId));
            if (memory.updateFact(entity, contactId, ownerUserId) != 1) {
                throw new ContactMemoryModels.ValidationException("PERSISTENCE_FAILED");
            }
            ContactMemoryFactEntity opposite = memory.findOppositeActiveFact(
                    ownerUserId, contactId, entity.getCategory(), entity.getNormalizedKey(),
                    entity.getNormalizedValue(), entity.getPolarity());
            if (opposite != null) {
                opposite.setStatus(ContactMemoryModels.FactStatus.CONFLICTED.name());
                opposite.setInvalidatedAt(now);
                opposite.setGenerationBatchId(result.generationBatchId());
                if (memory.updateFact(opposite, contactId, ownerUserId) != 1) {
                    throw new ContactMemoryModels.ValidationException("PERSISTENCE_FAILED");
                }
            }
            count++;
        }
        return count;
    }

    private int persistLabels(UUID ownerUserId, UUID contactId,
                              ContactMemoryModels.ConsolidationResult result) {
        int count = 0;
        Instant now = Instant.now();
        for (ContactMemoryModels.LabelCandidate candidate : result.labels()) {
            ContactAiLabelEntity entity = new ContactAiLabelEntity();
            entity.setCategory(candidate.category().name());
            entity.setNormalizedName(candidate.normalizedName());
            entity.setDisplayName(candidate.displayName());
            entity.setColorToken(candidate.colorToken());
            entity.setStatus(candidate.status().name());
            entity.setConfidence(candidate.confidence());
            entity.setFirstSeenAt(now);
            entity.setLastSeenAt(now);
            entity.setLastEvidenceAt(now);
            entity.setGenerationBatchId(result.generationBatchId());
            ContactAiLabelEntity stored = memory.findAiLabel(
                    ownerUserId, contactId, entity.getCategory(), entity.getNormalizedName());
            if (stored == null && candidate.status() != ContactMemoryModels.LabelStatus.ACTIVE) {
                throw new ContactMemoryModels.ValidationException("INVALID_OUTPUT");
            }
            if (stored == null) {
                entity.setId(UUID.randomUUID());
                if (memory.insertAiLabel(entity, contactId, ownerUserId) != 1) {
                    stored = memory.findAiLabel(ownerUserId, contactId, entity.getCategory(),
                            entity.getNormalizedName());
                } else {
                    stored = entity;
                }
            }
            if (stored == null || stored.getId() == null) {
                throw new ContactMemoryModels.ValidationException("PERSISTENCE_FAILED");
            }
            UUID factId = findSupportingFactId(ownerUserId, contactId, candidate, result);
            for (ContactMemoryModels.EvidenceRef evidence : candidate.evidence()) {
                ContactMemoryAiLabelEvidenceWriter.write(memory, stored, factId, contactId, ownerUserId,
                        evidence, result.generationBatchId());
            }
            entity.setId(stored.getId());
            if (memory.updateAiLabel(entity, contactId, ownerUserId) != 1) {
                throw new ContactMemoryModels.ValidationException("PERSISTENCE_FAILED");
            }
            count++;
        }
        return count;
    }

    private UUID findSupportingFactId(UUID ownerUserId, UUID contactId,
                                      ContactMemoryModels.LabelCandidate label,
                                      ContactMemoryModels.ConsolidationResult result) {
        for (ContactMemoryModels.FactCandidate fact : result.facts()) {
            if (fact.category() == label.category()
                    && (fact.normalizedKey().equals(label.normalizedName())
                    || fact.normalizedValue().equals(label.normalizedName()))) {
                ContactMemoryFactEntity entity = memory.findFact(ownerUserId, contactId,
                        fact.category().name(), fact.normalizedKey(), fact.normalizedValue(), fact.polarity().name());
                if (entity != null && entity.getId() != null) {
                    return entity.getId();
                }
            }
        }
        ContactMemoryFactEntity entity = memory.findActiveFactForLabel(
                ownerUserId, contactId, label.category().name(), label.normalizedName());
        if (entity != null && entity.getId() != null) {
            return entity.getId();
        }
        throw new ContactMemoryModels.ValidationException("INVALID_EVIDENCE");
    }

    private UUID persistProfile(UUID ownerUserId, UUID contactId,
                                ContactMemoryModels.ConsolidationResult result) {
        if (result.profile() == null) {
            return null;
        }
        memory.clearCurrentProfile(contactId, ownerUserId);
        ContactProfileVersionEntity entity = new ContactProfileVersionEntity();
        entity.setId(UUID.randomUUID());
        entity.setContactId(contactId);
        entity.setOwnerUserId(ownerUserId);
        entity.setVersion(memory.nextProfileVersion(contactId, ownerUserId));
        entity.setContent(result.profile().content());
        entity.setSourceCursor(result.outputCursor());
        entity.setGenerationBatchId(result.generationBatchId());
        entity.setModel(result.model());
        entity.setInputMessageCount(0);
        entity.setEvidenceCount(0);
        entity.setIsCurrent(true);
        if (memory.insertProfile(entity, contactId, ownerUserId) != 1) {
            throw new ContactMemoryModels.ValidationException("PERSISTENCE_FAILED");
        }
        return entity.getId();
    }

    private void persistAttempt(UUID ownerUserId, UUID contactId,
                                ContactMemoryModels.ConsolidationResult result,
                                boolean profileChanged) {
        ContactMemoryAttemptEntity entity = new ContactMemoryAttemptEntity();
        entity.setId(UUID.randomUUID());
        entity.setGenerationBatchId(result.generationBatchId());
        entity.setInputCursor(result.inputCursor());
        entity.setOutputCursor(result.outputCursor());
        entity.setStatus(ContactMemoryModels.AttemptStatus.SUCCEEDED.name());
        entity.setModel(result.model());
        entity.setInputMessageCount(0);
        entity.setOutputLabelChangeCount(result.labels().size());
        entity.setProfileChanged(profileChanged);
        entity.setRetryCount(0);
        entity.setCompletedAt(Instant.now());
        memory.insertAttempt(entity, contactId, ownerUserId);
    }

    private static void validateRequest(UUID ownerUserId, UUID contactId,
                                        ContactMemoryModels.Lease lease,
                                        ContactMemoryModels.ConsolidationResult result) {
        if (ownerUserId == null || contactId == null || lease == null || result == null
                || !ownerUserId.equals(lease.ownerUserId()) || !contactId.equals(lease.contactId())
                || lease.stateId() == null || lease.leaseOwner() == null || lease.leaseOwner().isBlank()
                || lease.leaseUntil() == null || !lease.leaseUntil().isAfter(Instant.now())
                || result.generationBatchId() == null) {
            throw new ContactMemoryModels.ValidationException("LEASE_LOST");
        }
    }

    private static void validateCandidates(ContactMemoryModels.ConsolidationResult result) {
        if (result.labels().size() > MAX_LABEL_CHANGES) {
            throw new ContactMemoryModels.ValidationException("OUTPUT_LIMIT");
        }
        if (result.profile() != null
                && result.profile().content().codePointCount(0, result.profile().content().length()) > 200) {
            throw new ContactMemoryModels.ValidationException("PROFILE_TOO_LONG");
        }
        for (ContactMemoryModels.FactCandidate fact : result.facts()) {
            if (fact.evidence().isEmpty()) {
                throw new ContactMemoryModels.ValidationException("INVALID_EVIDENCE");
            }
            for (ContactMemoryModels.EvidenceRef evidence : fact.evidence()) {
                if (evidence.type() == ContactMemoryModels.EvidenceType.PROFILE_VERSION
                        || evidence.type() == ContactMemoryModels.EvidenceType.LONG_TERM_FACT) {
                    throw new ContactMemoryModels.ValidationException("INVALID_EVIDENCE");
                }
            }
        }
        for (ContactMemoryModels.LabelCandidate label : result.labels()) {
            if (!List.of("blue", "green", "orange", "purple", "cyan", "red", "gray", "brown")
                    .contains(label.colorToken())) {
                throw new ContactMemoryModels.ValidationException("INVALID_OUTPUT");
            }
        }
    }

    private static final class ContactMemoryAiLabelEvidenceWriter {
        private static void write(ContactMemoryMapper memory, ContactAiLabelEntity label, UUID factId,
                                  UUID contactId, UUID ownerUserId, ContactMemoryModels.EvidenceRef evidence,
                                  UUID batchId) {
            ContactAiLabelEvidenceEntity entity = new ContactAiLabelEvidenceEntity();
            entity.setLabelId(label.getId());
            entity.setFactId(factId);
            entity.setEvidenceType(evidence.type().name());
            entity.setEvidenceId(evidence.id());
            entity.setEvidenceExcerpt("");
            entity.setGenerationBatchId(batchId);
            memory.insertAiLabelEvidence(entity, contactId, ownerUserId);
        }
    }
}
