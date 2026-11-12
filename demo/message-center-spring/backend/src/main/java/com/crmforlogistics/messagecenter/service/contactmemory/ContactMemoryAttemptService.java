package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.entity.ContactMemoryAttemptEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class ContactMemoryAttemptService {
    private final ContactMemoryMapper memory;

    public ContactMemoryAttemptService(ContactMemoryMapper memory) {
        this.memory = memory;
    }

    public ContactMemoryModels.AttemptRun start(UUID ownerUserId,
                                                UUID contactId,
                                                String inputCursor,
                                                int retryCount,
                                                Instant startedAt) {
        UUID attemptId = UUID.randomUUID();
        UUID generationBatchId = UUID.randomUUID();
        ContactMemoryAttemptEntity entity = new ContactMemoryAttemptEntity();
        entity.setId(attemptId);
        entity.setGenerationBatchId(generationBatchId);
        entity.setInputCursor(inputCursor);
        entity.setStatus(ContactMemoryModels.AttemptStatus.SKIPPED.name());
        entity.setInputMessageCount(0);
        entity.setOutputLabelChangeCount(0);
        entity.setProfileChanged(false);
        entity.setRetryCount(Math.max(0, Math.min(3, retryCount)));
        entity.setCreatedAt(startedAt);
        if (memory.insertAttempt(entity, contactId, ownerUserId) != 1) {
            throw new ContactMemoryModels.ValidationException("PERSISTENCE_FAILED");
        }
        return new ContactMemoryModels.AttemptRun(attemptId, generationBatchId, startedAt);
    }

    public void fail(ContactMemoryModels.AttemptRun run,
                      String failureCode,
                      String failureMessage,
                      int retryCount,
                      String inputCursor,
                      int inputMessageCount,
                      Instant completedAt) {
        int updated = memory.updateAttemptFailed(
                run.attemptId(), bounded(failureCode, 100), bounded(failureMessage, 2000),
                Math.max(0, Math.min(3, retryCount)), inputCursor, Math.max(0, inputMessageCount),
                durationMillis(run.startedAt(), completedAt), completedAt);
        if (updated != 1) {
            throw new ContactMemoryModels.ValidationException("PERSISTENCE_FAILED");
        }
    }

    public void succeed(ContactMemoryModels.AttemptRun run,
                         ContactMemoryModels.ConsolidationResult result,
                         UUID profileVersionId,
                         Instant completedAt) {
        int updated = memory.updateAttemptSucceeded(
                run.attemptId(), result.inputCursor(), result.outputCursor(), result.model(),
                durationMillis(run.startedAt(), completedAt), result.inputMessageCount(),
                result.labels().size(), profileVersionId != null, completedAt);
        if (updated != 1) {
            throw new ContactMemoryModels.ValidationException("PERSISTENCE_FAILED");
        }
    }

    public void skip(ContactMemoryModels.AttemptRun run,
                      String inputCursor,
                      String outputCursor,
                      int inputMessageCount,
                      Instant completedAt) {
        int updated = memory.updateAttemptSkipped(
                run.attemptId(), inputCursor, outputCursor, Math.max(0, inputMessageCount),
                durationMillis(run.startedAt(), completedAt), completedAt);
        if (updated != 1) {
            throw new ContactMemoryModels.ValidationException("PERSISTENCE_FAILED");
        }
    }

    private static long durationMillis(Instant startedAt, Instant completedAt) {
        if (startedAt == null || completedAt == null) {
            return 0;
        }
        return Math.max(0, Duration.between(startedAt, completedAt).toMillis());
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "CONTACT_MEMORY_FAILED";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
