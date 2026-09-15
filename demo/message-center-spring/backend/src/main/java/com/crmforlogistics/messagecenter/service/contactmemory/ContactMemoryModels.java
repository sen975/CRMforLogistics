package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.entity.CallTranscriptRevisionEntity;
import com.crmforlogistics.messagecenter.entity.ContactAiLabelEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEntity;
import com.crmforlogistics.messagecenter.entity.ContactProfileVersionEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ContactMemoryModels {
    private ContactMemoryModels() {
    }

    public enum Category {
        IDENTITY,
        PRODUCT_INTEREST,
        NEED,
        PERSONALITY_COMMUNICATION,
        DECISION_FACTOR,
        RISK,
        RELATIONSHIP_STAGE,
        OTHER_STABLE_TRAIT
    }

    public enum Polarity {
        POSITIVE,
        NEGATIVE,
        NEUTRAL
    }

    public enum EvidenceType {
        MESSAGE,
        TOPIC,
        CALL_TRANSCRIPT,
        LONG_TERM_FACT,
        PROFILE_VERSION
    }

    public enum ObservationStatus {
        CANDIDATE,
        PROMOTED,
        REJECTED,
        EXPIRED,
        MERGED
    }

    public enum FactStatus {
        ACTIVE,
        STALE,
        CONFLICTED,
        INACTIVE
    }

    public enum LabelStatus {
        ACTIVE,
        STALE,
        INACTIVE
    }

    public enum MemoryStateStatus {
        CLEAN,
        DIRTY,
        PROCESSING,
        RETRY_WAIT,
        FAILED
    }

    public enum LabelOperation {
        ADD,
        UPDATE,
        STALE,
        INACTIVATE,
        RESTORE
    }

    public enum AttemptStatus {
        SUCCEEDED,
        FAILED,
        SKIPPED
    }

    public enum ErrorCode {
        CONTEXT_LOAD_FAILED,
        INPUT_LIMIT,
        OUTPUT_LIMIT,
        LLM_TIMEOUT,
        LLM_RATE_LIMITED,
        LLM_UNAVAILABLE,
        INVALID_OUTPUT,
        INVALID_EVIDENCE,
        OWNER_MISMATCH,
        PERSISTENCE_FAILED,
        LEASE_LOST
    }

    public record Cursor(String value) {
    }

    public record Lease(UUID stateId, UUID contactId, UUID ownerUserId,
                        String leaseOwner, UUID leaseToken, Instant leaseUntil) {
    }

    public record AttemptRun(UUID attemptId, UUID generationBatchId, Instant startedAt) {
    }

    public record EvidenceRef(EvidenceType type, UUID id) {
    }

    public record ManualTag(UUID id, String name, String color) {
    }

    public record StableContext(ContactProfileVersionEntity currentProfile,
                                List<ContactMemoryFactEntity> activeFacts,
                                List<ContactAiLabelEntity> activeLabels,
                                List<AiTopicEntity> topics) {
        public StableContext {
            activeFacts = List.copyOf(activeFacts == null ? List.of() : activeFacts);
            activeLabels = List.copyOf(activeLabels == null ? List.of() : activeLabels);
            topics = List.copyOf(topics == null ? List.of() : topics);
        }
    }

    public record Context(UUID contactId,
                          UUID ownerUserId,
                          List<MessageEntity> inboundMessages,
                          List<ManualTag> manualTags,
                          ContactProfileVersionEntity currentProfile,
                          List<ContactMemoryObservationEntity> observations,
                          List<ContactMemoryFactEntity> activeFacts,
                          List<ContactAiLabelEntity> activeLabels,
                          StableContext stableContext,
                          List<AiTopicEntity> topics,
                          List<CallTranscriptRevisionEntity> callTranscripts,
                          String inputCursor,
                          String outputCursor) {
        public Context {
            inboundMessages = List.copyOf(inboundMessages == null ? List.of() : inboundMessages);
            manualTags = List.copyOf(manualTags == null ? List.of() : manualTags);
            observations = List.copyOf(observations == null ? List.of() : observations);
            activeFacts = List.copyOf(activeFacts == null ? List.of() : activeFacts);
            activeLabels = List.copyOf(activeLabels == null ? List.of() : activeLabels);
            topics = List.copyOf(topics == null ? List.of() : topics);
            callTranscripts = List.copyOf(callTranscripts == null ? List.of() : callTranscripts);
        }

        public Context(UUID contactId,
                       UUID ownerUserId,
                       List<MessageEntity> inboundMessages,
                       ContactProfileVersionEntity currentProfile,
                       List<ContactMemoryObservationEntity> observations,
                       List<ContactMemoryFactEntity> activeFacts,
                       List<ContactAiLabelEntity> activeLabels,
                       StableContext stableContext,
                       List<AiTopicEntity> topics,
                       List<CallTranscriptRevisionEntity> callTranscripts,
                       String inputCursor,
                       String outputCursor) {
            this(contactId, ownerUserId, inboundMessages, List.of(), currentProfile, observations,
                    activeFacts, activeLabels, stableContext, topics, callTranscripts,
                    inputCursor, outputCursor);
        }
    }

    public static boolean isValidAiLabelName(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 32) {
            return false;
        }
        boolean previousWhitespace = false;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) {
                if (codePoint == '\n' || codePoint == '\r' || previousWhitespace) {
                    return false;
                }
                previousWhitespace = true;
            } else {
                previousWhitespace = false;
            }
            offset += Character.charCount(codePoint);
        }
        int lastCodePoint = normalized.codePointBefore(normalized.length());
        if ("。！？!?；;：:,.，、".indexOf(lastCodePoint) >= 0) {
            return false;
        }
        return !normalized.matches("^(客户|他|她|对方|用户|本人|我们).*(希望|需要|想要|计划|正在|已经|会|将|喜欢|认为|确认|表示|要求).+");
    }

    public record ObservationCandidate(Category category,
                                        String normalizedKey,
                                        String observedValue,
                                        Polarity polarity,
                                        BigDecimal confidence,
                                        List<EvidenceRef> evidence,
                                        String reason) {
    }

    public record FactCandidate(Category category,
                                 String normalizedKey,
                                 String normalizedValue,
                                 String displayValue,
                                 Polarity polarity,
                                 FactStatus status,
                                 BigDecimal confidence,
                                 List<EvidenceRef> evidence) {
    }

    public record LabelCandidate(LabelOperation operation,
                                 Category category,
                                 String normalizedName,
                                 String displayName,
                                 String colorToken,
                                 LabelStatus status,
                                 BigDecimal confidence,
                                 List<EvidenceRef> evidence,
                                 String reason) {
    }

    public record ProfileCandidate(String content) {
    }

    public record LabelChange(LabelOperation operation,
                              Category category,
                              String name,
                              BigDecimal confidence,
                              List<EvidenceRef> evidence,
                              String reason) {
    }

    public record Noise(List<EvidenceRef> evidence, String reason) {
    }

    public record LlmOutput(List<ObservationCandidate> observations,
                            ProfileCandidate profile,
                            List<LabelChange> labelChanges,
                            List<Noise> noises,
                            String model,
                            String rawDiagnostics) {
        public LlmOutput {
            observations = List.copyOf(observations == null ? List.of() : observations);
            labelChanges = List.copyOf(labelChanges == null ? List.of() : labelChanges);
            noises = List.copyOf(noises == null ? List.of() : noises);
        }
    }

    public record ConsolidationResult(UUID generationBatchId,
                                      String inputCursor,
                                      String outputCursor,
                                      List<ObservationCandidate> observations,
                                      List<FactCandidate> facts,
                                      List<LabelCandidate> labels,
                                      ProfileCandidate profile,
                                      String model,
                                      int inputMessageCount,
                                      int evidenceCount,
                                      String profileInputSource) {
        public ConsolidationResult(UUID generationBatchId,
                                   String inputCursor,
                                   String outputCursor,
                                   List<ObservationCandidate> observations,
                                   List<FactCandidate> facts,
                                   List<LabelCandidate> labels,
                                   ProfileCandidate profile,
                                   String model) {
            this(generationBatchId, inputCursor, outputCursor, observations, facts, labels,
                    profile, model, 0, 0, null);
        }

        public ConsolidationResult {
            observations = List.copyOf(observations == null ? List.of() : observations);
            facts = List.copyOf(facts == null ? List.of() : facts);
            labels = List.copyOf(labels == null ? List.of() : labels);
            if (inputMessageCount < 0 || evidenceCount < 0) {
                throw new ValidationException("INVALID_OUTPUT");
            }
        }
    }

    public record MutationResult(UUID profileVersionId,
                                 String outputCursor,
                                 int observationCount,
                                 int factCount,
                                 int labelCount) {
    }

    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }
    }
}
