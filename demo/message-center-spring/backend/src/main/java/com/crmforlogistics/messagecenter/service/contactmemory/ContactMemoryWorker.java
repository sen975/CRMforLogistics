package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.config.ContactMemoryConfig;
import com.crmforlogistics.messagecenter.entity.ContactMemoryStateEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ContactMemoryWorker {
    private static final Logger log = LoggerFactory.getLogger(ContactMemoryWorker.class);

    private final ContactMemoryStateMapper states;
    private final ContactMemoryContextService contextService;
    private final ContactMemoryLlmGateway gateway;
    private final ContactMemoryConsolidationService consolidation;
    private final ContactMemoryMutationService mutation;
    private final ContactMemoryAttemptService attempts;
    private final ContactMemoryConfig config;
    private final String workerId;

    @Autowired
    public ContactMemoryWorker(ContactMemoryStateMapper states,
                                ContactMemoryContextService contextService,
                                ContactMemoryLlmGateway gateway,
                                ContactMemoryConsolidationService consolidation,
                                ContactMemoryMutationService mutation,
                                ContactMemoryAttemptService attempts,
                                ContactMemoryConfig config) {
        this(states, contextService, gateway, consolidation, mutation, attempts, config,
                "contact-memory-" + UUID.randomUUID());
    }

    public ContactMemoryWorker(ContactMemoryStateMapper states,
                               ContactMemoryContextService contextService,
                               ContactMemoryLlmGateway gateway,
                               ContactMemoryConsolidationService consolidation,
                               ContactMemoryMutationService mutation,
                               ContactMemoryAttemptService attempts,
                               ContactMemoryConfig config,
                               String workerId) {
        this.states = states;
        this.contextService = contextService;
        this.gateway = gateway;
        this.consolidation = consolidation;
        this.mutation = mutation;
        this.attempts = attempts;
        this.config = config;
        this.workerId = workerId == null || workerId.isBlank()
                ? "contact-memory-" + UUID.randomUUID()
                : workerId;
    }

    public int runOnce(Instant now) {
        if (now == null) {
            return 0;
        }
        states.markStaleDirty(now, config.batchSize());
        List<ContactMemoryStateEntity> runnable = states.listRunnable(now, null, config.batchSize());
        if (runnable == null || runnable.isEmpty()) {
            return 0;
        }
        for (ContactMemoryStateEntity state : runnable) {
            process(state, now);
        }
        return runnable.size();
    }

    public void process(ContactMemoryStateEntity state, Instant now) {
        if (state == null || now == null || state.getId() == null
                || state.getContactId() == null || state.getOwnerUserId() == null) {
            return;
        }
        String leaseOwner = workerId + "/" + state.getId();
        Instant leaseUntil = now.plusSeconds(config.leaseSeconds());
        Optional<UUID> leaseToken = states.claim(state.getId(), leaseOwner, leaseUntil);
        if (leaseToken.isEmpty()) {
            return;
        }

        ContactMemoryModels.Lease lease = new ContactMemoryModels.Lease(
                state.getId(), state.getContactId(), state.getOwnerUserId(), leaseOwner,
                leaseToken.get(), leaseUntil);
        ContactMemoryModels.AttemptRun attempt = null;
        ContactMemoryModels.Context context = null;
        try {
            attempt = attempts.start(state.getOwnerUserId(), state.getContactId(),
                    state.getLastSuccessCursor(), state.getRetryCount() == null ? 0 : state.getRetryCount(), now);
            context = contextService.load(
                    state.getOwnerUserId(), state.getContactId(), now);
            if (context.inboundMessages().isEmpty()) {
                attempts.skip(attempt, context.inputCursor(), context.outputCursor(),
                        context.inboundMessages().size(), Instant.now());
                completeWithoutLlm(state, lease, context.outputCursor(), now);
                return;
            }

            ContactMemoryModels.LlmOutput output = gateway.generate(context);
            ContactMemoryModels.ConsolidationResult result = consolidation.consolidate(
                    context, output, attempt.generationBatchId());
            mutation.persist(state.getOwnerUserId(), state.getContactId(), lease, result, attempt);
        } catch (ContactMemoryLlmGateway.GatewayException exception) {
            handleFailure(state, lease, attempt, context, now, exception.code(),
                    exception.diagnostic(), exception.retryable());
        } catch (ContactMemoryModels.ValidationException exception) {
            String code = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? "PERSISTENCE_FAILED" : exception.getMessage();
            handleFailure(state, lease, attempt, context, now, code, code, !isTerminalValidation(code));
        } catch (RuntimeException exception) {
            handleFailure(state, lease, attempt, context, now, "PERSISTENCE_FAILED",
                    exception.getMessage() == null ? exception.getClass().getSimpleName()
                            : exception.getMessage(), true);
        }
    }

    private void completeWithoutLlm(ContactMemoryStateEntity state,
                                    ContactMemoryModels.Lease lease,
                                    String cursor,
                                    Instant now) {
        if (states.complete(state.getId(), lease.leaseToken(), cursor, null, now) != 1) {
            throw new ContactMemoryModels.ValidationException("LEASE_LOST");
        }
    }

    private void handleFailure(ContactMemoryStateEntity state,
                               ContactMemoryModels.Lease lease,
                               ContactMemoryModels.AttemptRun attemptRun,
                               ContactMemoryModels.Context context,
                               Instant now,
                               String code,
                               String message,
                               boolean retryable) {
        int previousAttempts = state.getRetryCount() == null ? 0 : state.getRetryCount();
        int attempt = Math.min(previousAttempts + 1, config.maxAttempts());
        boolean terminal = !retryable || attempt >= config.maxAttempts();
        Instant retryAt = terminal ? now : now.plusSeconds(backoffSeconds(attempt));
        try {
            states.fail(state.getId(), lease.leaseToken(), bounded(code, 100), bounded(message, 1000),
                    attempt, retryAt, terminal);
        } catch (RuntimeException failure) {
            log.warn("contact memory failure state update failed stateId={}", state.getId(), failure);
        }
        if (attemptRun != null) {
            try {
                attempts.fail(attemptRun, code, message, attempt,
                        context == null ? state.getLastSuccessCursor() : context.inputCursor(),
                        context == null ? 0 : context.inboundMessages().size(), Instant.now());
            } catch (RuntimeException failure) {
                log.warn("contact memory attempt audit failed stateId={}", state.getId(), failure);
            }
        }
    }

    private long backoffSeconds(int attempt) {
        long backoff = config.leaseSeconds();
        for (int index = 1; index < attempt; index++) {
            if (backoff >= config.maxBackoffSeconds()) {
                return config.maxBackoffSeconds();
            }
            backoff = Math.min(config.maxBackoffSeconds(), backoff * 2);
        }
        return Math.min(config.maxBackoffSeconds(), backoff);
    }

    private static boolean isTerminalValidation(String code) {
        return "OWNER_MISMATCH".equals(code)
                || "OWNER_NOT_FOUND".equals(code)
                || "INVALID_OUTPUT".equals(code)
                || "INVALID_EVIDENCE".equals(code)
                || "PROFILE_TOO_LONG".equals(code)
                || "INPUT_LIMIT".equals(code)
                || "OUTPUT_LIMIT".equals(code)
                || "INVALID_CURSOR".equals(code);
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "CONTACT_MEMORY_FAILED";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
