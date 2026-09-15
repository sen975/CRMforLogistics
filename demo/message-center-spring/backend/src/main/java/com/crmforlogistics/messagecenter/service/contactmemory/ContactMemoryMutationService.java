package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.entity.ContactAiLabelEntity;
import com.crmforlogistics.messagecenter.entity.ContactAiLabelEvidenceEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryFactEvidenceEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEvidenceEntity;
import com.crmforlogistics.messagecenter.entity.ContactProfileVersionEntity;
import com.crmforlogistics.messagecenter.config.ContactMemoryConfig;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class ContactMemoryMutationService {
    private static final int MAX_LABEL_CHANGES = 20;
    private final ContactMemoryMapper memory;
    private final ContactMemoryStateMapper states;
    private final ContactMemoryAttemptService attempts;
    private final ContactMemoryConfig config;

    public ContactMemoryMutationService(ContactMemoryMapper memory, ContactMemoryStateMapper states) {
        this(memory, states, new ContactMemoryAttemptService(memory), new ContactMemoryConfig(
                50, 4000, 50000, 20, 1000, 10, 4000,
                100, 100, 100, 500, 30));
    }

    public ContactMemoryMutationService(ContactMemoryMapper memory,
                                        ContactMemoryStateMapper states,
                                        ContactMemoryConfig config) {
        this(memory, states, new ContactMemoryAttemptService(memory), config);
    }

    public ContactMemoryMutationService(ContactMemoryMapper memory,
                                        ContactMemoryStateMapper states,
                                        ContactMemoryAttemptService attempts) {
        this(memory, states, attempts, new ContactMemoryConfig(
                50, 4000, 50000, 20, 1000, 10, 4000,
                100, 100, 100, 500, 30));
    }

    @Autowired
    public ContactMemoryMutationService(ContactMemoryMapper memory,
                                        ContactMemoryStateMapper states,
                                        ContactMemoryAttemptService attempts,
                                        ContactMemoryConfig config) {
        this.memory = memory;
        this.states = states;
        this.attempts = attempts;
        this.config = config;
    }

    @Transactional
    public ContactMemoryModels.MutationResult persist(
            UUID ownerUserId,
            UUID contactId,
            ContactMemoryModels.Lease lease,
            ContactMemoryModels.ConsolidationResult result,
            ContactMemoryModels.AttemptRun attempt) {
        validateRequest(ownerUserId, contactId, lease, result, attempt);
        validateCandidates(result);

        memory.expireObservations(contactId, ownerUserId, Instant.now());
        int observationCount = persistObservations(ownerUserId, contactId, result);
        int factCount = persistFacts(ownerUserId, contactId, result);
        int labelCount = persistLabels(ownerUserId, contactId, result);
        UUID profileId = persistProfile(ownerUserId, contactId, result);
        ContactMemoryModels.MutationResult mutationResult = new ContactMemoryModels.MutationResult(
                profileId, result.outputCursor(), observationCount, factCount, labelCount);
        attempts.succeed(attempt, result, profileId, Instant.now());

        if (states.complete(lease.stateId(), lease.leaseToken(), result.outputCursor(), profileId,
                Instant.now()) != 1) {
            throw new ContactMemoryModels.ValidationException("LEASE_LOST");
        }
        return mutationResult;
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
            entity.setExpiresAt(now.plus(config.observationTtlDays(), ChronoUnit.DAYS));
            UUID storedObservationId = memory.upsertObservation(entity, contactId, ownerUserId);
            if (storedObservationId == null) {
                throw new ContactMemoryModels.ValidationException("PERSISTENCE_FAILED");
            }
            entity.setId(storedObservationId);
            if (memory.updateObservation(entity, contactId, ownerUserId) != 1) {
                throw new ContactMemoryModels.ValidationException("PERSISTENCE_FAILED");
            }
            for (ContactMemoryModels.EvidenceRef evidence : candidate.evidence()) {
                ContactMemoryObservationEvidenceEntity evidenceEntity = new ContactMemoryObservationEvidenceEntity();
                evidenceEntity.setObservationId(storedObservationId);
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
            ContactMemoryObservationEntity promotedObservation = memory.findCandidateObservation(
                    ownerUserId, contactId, entity.getCategory(), entity.getNormalizedKey(),
                    entity.getNormalizedValue(), entity.getPolarity());
            if (promotedObservation != null && promotedObservation.getId() != null) {
                memory.copyObservationEvidenceToFact(promotedObservation.getId(), stored.getId(),
                        contactId, ownerUserId, result.generationBatchId());
            }
            entity.setId(stored.getId());
            entity.setEvidenceCount((int) memory.countFactEvidence(stored.getId(), contactId, ownerUserId));
            if (memory.updateFact(entity, contactId, ownerUserId) != 1) {
                throw new ContactMemoryModels.ValidationException("PERSISTENCE_FAILED");
            }
            if (promotedObservation != null && promotedObservation.getId() != null
                    && memory.updateObservationLifecycle(promotedObservation.getId(), contactId, ownerUserId,
                    ContactMemoryModels.ObservationStatus.PROMOTED.name(), stored.getId()) != 1) {
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
        entity.setInputMessageCount(result.inputMessageCount());
        entity.setEvidenceCount(result.evidenceCount());
        entity.setIsCurrent(true);
        if (memory.insertProfile(entity, contactId, ownerUserId) != 1) {
            throw new ContactMemoryModels.ValidationException("PERSISTENCE_FAILED");
        }
        return entity.getId();
    }

    private static void validateRequest(UUID ownerUserId, UUID contactId,
                                        ContactMemoryModels.Lease lease,
                                        ContactMemoryModels.ConsolidationResult result,
                                        ContactMemoryModels.AttemptRun attempt) {
        if (ownerUserId == null || contactId == null || lease == null || result == null
                || attempt == null || attempt.attemptId() == null || attempt.generationBatchId() == null
                || !attempt.generationBatchId().equals(result.generationBatchId())
                || !ownerUserId.equals(lease.ownerUserId()) || !contactId.equals(lease.contactId())
                || lease.stateId() == null || lease.leaseOwner() == null || lease.leaseOwner().isBlank()
                || lease.leaseToken() == null
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
