package com.crmforlogistics.messagecenter.service.contactmemory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ContactMemoryConsolidationService {
    private static final int MAX_LABEL_CHANGES = 20;
    private static final Map<ContactMemoryModels.Category, String> COLORS = colors();

    private final Clock clock;

    @Autowired
    public ContactMemoryConsolidationService() {
        this(Clock.systemUTC());
    }

    ContactMemoryConsolidationService(Clock clock) {
        this.clock = clock;
    }

    public ContactMemoryModels.ConsolidationResult consolidate(
            ContactMemoryModels.Context context,
            ContactMemoryModels.LlmOutput output) {
        return consolidate(context, output, UUID.randomUUID());
    }

    public ContactMemoryModels.ConsolidationResult consolidate(
            ContactMemoryModels.Context context,
            ContactMemoryModels.LlmOutput output,
            UUID generationBatchId) {
        if (context == null || output == null || context.contactId() == null
                || context.ownerUserId() == null || generationBatchId == null) {
            throw new ContactMemoryModels.ValidationException("INVALID_OUTPUT");
        }
        if (output.labelChanges().size() > MAX_LABEL_CHANGES) {
            throw new ContactMemoryModels.ValidationException("OUTPUT_LIMIT");
        }
        validateContextEvidence(context, output);

        Instant now = clock.instant();
        List<ContactMemoryModels.ObservationCandidate> observations = consolidateObservations(
                output.observations());
        List<ContactMemoryModels.FactCandidate> facts = promoteFacts(context, observations, now);
        List<ContactMemoryModels.LabelCandidate> labels = output.labelChanges().stream()
                .map(change -> toLabelCandidate(change))
                .toList();
        ContactMemoryModels.ProfileCandidate profile = normalizeProfile(output.profile());
        int evidenceCount = countEvidence(observations, facts, labels);

        return new ContactMemoryModels.ConsolidationResult(
                generationBatchId,
                context.inputCursor(),
                context.outputCursor(),
                observations,
                facts,
                labels,
                profile,
                boundedModel(output.model()),
                context.inboundMessages().size(),
                evidenceCount,
                context.currentProfile() == null ? "NEW_CONTEXT" : "CURRENT_PROFILE_PLUS_NEW_CONTEXT");
    }

    public String normalizeKey(String category, String value) {
        String normalized = normalizeText(value);
        if (normalized.isEmpty()) {
            throw new ContactMemoryModels.ValidationException("INVALID_OUTPUT");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    public String colorToken(String category) {
        try {
            return COLORS.getOrDefault(ContactMemoryModels.Category.valueOf(category), "brown");
        } catch (IllegalArgumentException | NullPointerException ignored) {
            throw new ContactMemoryModels.ValidationException("INVALID_OUTPUT");
        }
    }

    private List<ContactMemoryModels.ObservationCandidate> consolidateObservations(
            List<ContactMemoryModels.ObservationCandidate> source) {
        Map<String, ContactMemoryModels.ObservationCandidate> merged = new LinkedHashMap<>();
        for (ContactMemoryModels.ObservationCandidate candidate : source) {
            validateObservation(candidate);
            String key = candidate.category().name() + "|"
                    + normalizeKey(candidate.category().name(), candidate.normalizedKey()) + "|"
                    + normalizeText(candidate.observedValue()).toLowerCase(Locale.ROOT) + "|"
                    + candidate.polarity().name();
            merged.merge(key, candidate, this::mergeObservation);
        }
        return List.copyOf(merged.values());
    }

    private List<ContactMemoryModels.FactCandidate> promoteFacts(
            ContactMemoryModels.Context context,
            List<ContactMemoryModels.ObservationCandidate> observations,
            Instant now) {
        Map<String, Integer> existingEvidence = new HashMap<>();
        for (ContactMemoryModels.FactCandidate fact : context.activeFacts().stream()
                .map(ContactMemoryConsolidationService::toFactCandidate).toList()) {
            int evidenceCount = context.activeFacts().stream()
                    .filter(entity -> factKey(toFactCandidate(entity)).equals(factKey(fact)))
                    .map(com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity::getEvidenceCount)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(0);
            existingEvidence.merge(factKey(fact), evidenceCount, Math::max);
        }
        Map<String, Integer> historicalObservationEvidence = new HashMap<>();
        Map<String, List<ContactMemoryModels.EvidenceRef>> historicalEvidence = new HashMap<>();
        for (com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEntity observation
                : context.observations()) {
            if (!ContactMemoryModels.ObservationStatus.CANDIDATE.name().equals(observation.getStatus())
                    || (observation.getExpiresAt() != null && !observation.getExpiresAt().isAfter(now))) {
                continue;
            }
            String key = observationKey(observation);
            List<ContactMemoryModels.EvidenceRef> refs = observation.getEvidence().stream()
                    .filter(evidence -> evidence.getEvidenceType() != null && evidence.getEvidenceId() != null)
                    .map(evidence -> new ContactMemoryModels.EvidenceRef(
                            ContactMemoryModels.EvidenceType.valueOf(evidence.getEvidenceType()),
                            evidence.getEvidenceId()))
                    .toList();
            historicalEvidence.merge(key, refs, ContactMemoryConsolidationService::mergeEvidence);
            int count = observation.getEvidenceCount() == null
                    ? distinctEvidence(refs) : observation.getEvidenceCount();
            historicalObservationEvidence.merge(key, count, Math::max);
        }
        Map<String, ContactMemoryModels.ObservationCandidate> candidates = observations.stream()
                .collect(Collectors.toMap(this::observationKey, Function.identity(), this::mergeObservation,
                        LinkedHashMap::new));
        List<ContactMemoryModels.FactCandidate> facts = new ArrayList<>();
        for (ContactMemoryModels.ObservationCandidate candidate : candidates.values()) {
            String key = observationKey(candidate);
            List<ContactMemoryModels.EvidenceRef> priorRefs = historicalEvidence.getOrDefault(key, List.of());
            int previousEvidence = Math.max(existingEvidence.getOrDefault(key, 0),
                    historicalObservationEvidence.getOrDefault(key, 0));
            int newEvidence = distinctEvidence(candidate.evidence().stream()
                    .filter(evidence -> !containsEvidence(priorRefs, evidence))
                    .toList());
            if (previousEvidence + newEvidence < 2) {
                continue;
            }
            List<ContactMemoryModels.EvidenceRef> factEvidence = mergeEvidence(priorRefs, candidate.evidence());
            facts.add(new ContactMemoryModels.FactCandidate(
                    candidate.category(),
                    normalizeKey(candidate.category().name(), candidate.normalizedKey()),
                    normalizeValue(candidate.observedValue()),
                    normalizeText(candidate.observedValue()),
                    candidate.polarity(),
                    ContactMemoryModels.FactStatus.ACTIVE,
                    candidate.confidence(),
                    factEvidence));
        }
        return List.copyOf(facts);
    }

    private ContactMemoryModels.LabelCandidate toLabelCandidate(ContactMemoryModels.LabelChange change) {
        if (change == null || change.operation() == null || change.category() == null
                || change.evidence() == null || change.evidence().isEmpty()) {
            throw new ContactMemoryModels.ValidationException("INVALID_OUTPUT");
        }
        String name = normalizeText(change.name());
        if (!ContactMemoryModels.isValidAiLabelName(name) || !validConfidence(change.confidence())) {
            throw new ContactMemoryModels.ValidationException("INVALID_OUTPUT");
        }
        return new ContactMemoryModels.LabelCandidate(
                change.operation(),
                change.category(),
                normalizeKey(change.category().name(), name),
                name,
                colorToken(change.category().name()),
                labelStatus(change.operation()),
                change.confidence(),
                List.copyOf(change.evidence()),
                boundedReason(change.reason()));
    }

    private static ContactMemoryModels.ProfileCandidate normalizeProfile(
            ContactMemoryModels.ProfileCandidate profile) {
        if (profile == null) {
            return null;
        }
        String content = normalizeText(profile.content());
        if (content.isEmpty() || content.codePointCount(0, content.length()) > 200) {
            throw new ContactMemoryModels.ValidationException("PROFILE_TOO_LONG");
        }
        return new ContactMemoryModels.ProfileCandidate(content);
    }

    private ContactMemoryModels.ObservationCandidate mergeObservation(
            ContactMemoryModels.ObservationCandidate first,
            ContactMemoryModels.ObservationCandidate second) {
        return new ContactMemoryModels.ObservationCandidate(
                first.category(),
                normalizeKey(first.category().name(), first.normalizedKey()),
                normalizeText(first.observedValue()),
                first.polarity(),
                first.confidence().max(second.confidence()),
                mergeEvidence(first.evidence(), second.evidence()),
                boundedReason(first.reason() == null ? second.reason() : first.reason()));
    }

    private void validateObservation(ContactMemoryModels.ObservationCandidate candidate) {
        if (candidate == null || candidate.category() == null || candidate.polarity() == null
                || !validConfidence(candidate.confidence()) || normalizeText(candidate.observedValue()).isEmpty()
                || normalizeText(candidate.observedValue()).length() > 500
                || candidate.evidence() == null || candidate.evidence().isEmpty()) {
            throw new ContactMemoryModels.ValidationException("INVALID_OUTPUT");
        }
        if (normalizeText(candidate.normalizedKey()).isEmpty()) {
            throw new ContactMemoryModels.ValidationException("INVALID_OUTPUT");
        }
        for (ContactMemoryModels.EvidenceRef evidence : candidate.evidence()) {
            validateEvidenceRef(evidence);
        }
    }

    private static void validateEvidenceRef(ContactMemoryModels.EvidenceRef evidence) {
        if (evidence == null || evidence.type() == null || evidence.id() == null
                || evidence.type() == ContactMemoryModels.EvidenceType.PROFILE_VERSION) {
            throw new ContactMemoryModels.ValidationException("INVALID_EVIDENCE");
        }
    }

    private static void validateContextEvidence(ContactMemoryModels.Context context,
                                                ContactMemoryModels.LlmOutput output) {
        Set<UUID> messageIds = context.inboundMessages().stream()
                .map(com.crmforlogistics.messagecenter.entity.MessageEntity::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Set<UUID> topicIds = context.topics().stream()
                .map(com.crmforlogistics.messagecenter.entity.AiTopicEntity::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Set<UUID> transcriptIds = context.callTranscripts().stream()
                .map(com.crmforlogistics.messagecenter.entity.CallTranscriptRevisionEntity::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Set<UUID> factIds = context.activeFacts().stream()
                .map(com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        UUID profileId = context.currentProfile() == null ? null : context.currentProfile().getId();

        for (ContactMemoryModels.ObservationCandidate observation : output.observations()) {
            for (ContactMemoryModels.EvidenceRef evidence : observation.evidence()) {
                validateEvidenceRef(evidence);
                if (!matchesContext(evidence, messageIds, topicIds, transcriptIds, Set.of(), null)) {
                    throw new ContactMemoryModels.ValidationException("INVALID_EVIDENCE");
                }
            }
        }
        for (ContactMemoryModels.LabelChange label : output.labelChanges()) {
            if (label == null || label.evidence() == null) {
                throw new ContactMemoryModels.ValidationException("INVALID_OUTPUT");
            }
            for (ContactMemoryModels.EvidenceRef evidence : label.evidence()) {
                if (evidence == null || evidence.type() == null || evidence.id() == null) {
                    throw new ContactMemoryModels.ValidationException("INVALID_EVIDENCE");
                }
                if (!matchesContext(evidence, messageIds, topicIds, transcriptIds, factIds, profileId)) {
                    throw new ContactMemoryModels.ValidationException("INVALID_EVIDENCE");
                }
            }
        }
    }

    private static boolean matchesContext(ContactMemoryModels.EvidenceRef evidence,
                                          Set<UUID> messageIds,
                                          Set<UUID> topicIds,
                                          Set<UUID> transcriptIds,
                                          Set<UUID> factIds,
                                          UUID profileId) {
        return switch (evidence.type()) {
            case MESSAGE -> messageIds.contains(evidence.id());
            case TOPIC -> topicIds.contains(evidence.id());
            case CALL_TRANSCRIPT -> transcriptIds.contains(evidence.id());
            case LONG_TERM_FACT -> factIds.contains(evidence.id());
            case PROFILE_VERSION -> profileId != null && profileId.equals(evidence.id());
        };
    }

    private static ContactMemoryModels.FactCandidate toFactCandidate(
            com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity entity) {
        return new ContactMemoryModels.FactCandidate(
                ContactMemoryModels.Category.valueOf(entity.getCategory()), entity.getNormalizedKey(),
                entity.getNormalizedValue(), entity.getDisplayValue(),
                ContactMemoryModels.Polarity.valueOf(entity.getPolarity()),
                ContactMemoryModels.FactStatus.valueOf(entity.getStatus()), entity.getConfidence(), List.of());
    }

    private String observationKey(ContactMemoryModels.ObservationCandidate candidate) {
        return candidate.category().name() + "|"
                + normalizeKey(candidate.category().name(), candidate.normalizedKey()) + "|"
                + normalizeValue(candidate.observedValue()) + "|" + candidate.polarity().name();
    }

    private String observationKey(
            com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEntity observation) {
        return observation.getCategory() + "|"
                + normalizeKey(observation.getCategory(), observation.getNormalizedKey()) + "|"
                + normalizeValue(observation.getObservedValue()) + "|" + observation.getPolarity();
    }

    private static String factKey(ContactMemoryModels.FactCandidate fact) {
        return fact.category().name() + "|" + fact.normalizedKey() + "|"
                + fact.normalizedValue() + "|" + fact.polarity().name();
    }

    private static int distinctEvidence(List<ContactMemoryModels.EvidenceRef> evidence) {
        return (int) evidence.stream().filter(item -> item != null && item.id() != null).distinct().count();
    }

    private static int countEvidence(
            List<ContactMemoryModels.ObservationCandidate> observations,
            List<ContactMemoryModels.FactCandidate> facts,
            List<ContactMemoryModels.LabelCandidate> labels) {
        Set<String> evidence = new HashSet<>();
        observations.forEach(item -> item.evidence().forEach(ref -> addEvidence(evidence, ref)));
        facts.forEach(item -> item.evidence().forEach(ref -> addEvidence(evidence, ref)));
        labels.forEach(item -> item.evidence().forEach(ref -> addEvidence(evidence, ref)));
        return evidence.size();
    }

    private static void addEvidence(Set<String> evidence, ContactMemoryModels.EvidenceRef ref) {
        if (ref != null && ref.type() != null && ref.id() != null) {
            evidence.add(ref.type().name() + "|" + ref.id());
        }
    }

    private static boolean containsEvidence(List<ContactMemoryModels.EvidenceRef> evidence,
                                            ContactMemoryModels.EvidenceRef candidate) {
        return evidence.stream().anyMatch(item -> item.type() == candidate.type()
                && item.id().equals(candidate.id()));
    }

    private static List<ContactMemoryModels.EvidenceRef> mergeEvidence(
            List<ContactMemoryModels.EvidenceRef> first,
            List<ContactMemoryModels.EvidenceRef> second) {
        Map<String, ContactMemoryModels.EvidenceRef> merged = new LinkedHashMap<>();
        for (ContactMemoryModels.EvidenceRef evidence : List.of(first, second).stream()
                .flatMap(List::stream).toList()) {
            merged.put(evidence.type().name() + "|" + evidence.id(), evidence);
        }
        return List.copyOf(merged.values());
    }

    private static ContactMemoryModels.LabelStatus labelStatus(ContactMemoryModels.LabelOperation operation) {
        return switch (operation) {
            case ADD, UPDATE, RESTORE -> ContactMemoryModels.LabelStatus.ACTIVE;
            case STALE -> ContactMemoryModels.LabelStatus.STALE;
            case INACTIVATE -> ContactMemoryModels.LabelStatus.INACTIVE;
        };
    }

    private static Map<ContactMemoryModels.Category, String> colors() {
        Map<ContactMemoryModels.Category, String> result = new EnumMap<>(ContactMemoryModels.Category.class);
        result.put(ContactMemoryModels.Category.IDENTITY, "blue");
        result.put(ContactMemoryModels.Category.PRODUCT_INTEREST, "green");
        result.put(ContactMemoryModels.Category.NEED, "orange");
        result.put(ContactMemoryModels.Category.PERSONALITY_COMMUNICATION, "purple");
        result.put(ContactMemoryModels.Category.DECISION_FACTOR, "cyan");
        result.put(ContactMemoryModels.Category.RISK, "red");
        result.put(ContactMemoryModels.Category.RELATIONSHIP_STAGE, "gray");
        result.put(ContactMemoryModels.Category.OTHER_STABLE_TRAIT, "brown");
        return Map.copyOf(result);
    }

    private static String normalizeValue(String value) {
        return normalizeText(value).toLowerCase(Locale.ROOT);
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String boundedReason(String reason) {
        String value = normalizeText(reason);
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static String boundedModel(String model) {
        String value = normalizeText(model);
        return value.length() <= 150 ? value : value.substring(0, 150);
    }

    private static boolean validConfidence(BigDecimal confidence) {
        return confidence != null && confidence.signum() >= 0 && confidence.compareTo(BigDecimal.ONE) <= 0;
    }
}
