package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.config.ContactMemoryConfig;
import com.crmforlogistics.messagecenter.entity.ContactMemoryStateEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContactMemoryWorkerTest {
    private static final Instant NOW = Instant.parse("2026-09-11T16:00:00Z");
    private static final UUID CONTACT_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();

    @Test
    void processesClaimedStateThroughContextGatewayConsolidationAndMutation() {
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryContextService contextService = mock(ContactMemoryContextService.class);
        ContactMemoryLlmGateway gateway = mock(ContactMemoryLlmGateway.class);
        ContactMemoryConsolidationService consolidation = mock(ContactMemoryConsolidationService.class);
        ContactMemoryMutationService mutation = mock(ContactMemoryMutationService.class);
        ContactMemoryStateEntity state = state("DIRTY", 0);
        ContactMemoryModels.Context context = contextWithInbound("out");
        ContactMemoryModels.LlmOutput output = new ContactMemoryModels.LlmOutput(
                List.of(), null, List.of(), List.of(), "model", "{}");
        ContactMemoryModels.ConsolidationResult result = new ContactMemoryModels.ConsolidationResult(
                UUID.randomUUID(), null, "out", List.of(), List.of(), List.of(), null, "model");
        when(states.claim(eq(state.getId()), any(), any())).thenReturn(Optional.of(UUID.randomUUID()));
        when(contextService.load(OWNER_ID, CONTACT_ID, NOW)).thenReturn(context);
        when(gateway.generate(context)).thenReturn(output);
        when(consolidation.consolidate(eq(context), eq(output), any())).thenReturn(result);

        ContactMemoryWorker worker = worker(states, contextService, gateway, consolidation, mutation);

        worker.process(state, NOW);

        verify(gateway).generate(context);
        verify(consolidation).consolidate(eq(context), eq(output), any());
        verify(mutation).persist(eq(OWNER_ID), eq(CONTACT_ID), any(), eq(result), any());
        verify(states, never()).fail(any(), any(), any(), any(), any(Integer.class),
                any(), any(Boolean.class));
    }

    @Test
    void cleanStateWithNoNewInboundDoesNotCallGateway() {
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryContextService contextService = mock(ContactMemoryContextService.class);
        ContactMemoryLlmGateway gateway = mock(ContactMemoryLlmGateway.class);
        ContactMemoryConsolidationService consolidation = mock(ContactMemoryConsolidationService.class);
        ContactMemoryMutationService mutation = mock(ContactMemoryMutationService.class);
        ContactMemoryStateEntity state = state("DIRTY", 0);
        ContactMemoryModels.Context context = contextWithInbound();
        when(states.claim(eq(state.getId()), any(), any())).thenReturn(Optional.of(UUID.randomUUID()));
        when(contextService.load(OWNER_ID, CONTACT_ID, NOW)).thenReturn(context);
        when(states.complete(eq(state.getId()), any(), eq("cursor"), eq((UUID) null), eq(NOW)))
                .thenReturn(1);

        ContactMemoryWorker worker = worker(states, contextService, gateway, consolidation, mutation);

        worker.process(state, NOW);

        verify(gateway, never()).generate(any());
        verify(consolidation, never()).consolidate(any(), any());
        verify(mutation, never()).persist(any(), any(), any(), any(), any());
        verify(states).complete(eq(state.getId()), any(), eq("cursor"), eq((UUID) null), eq(NOW));
    }

    @Test
    void retryableGatewayFailureUsesCappedExponentialBackoff() {
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryContextService contextService = mock(ContactMemoryContextService.class);
        ContactMemoryLlmGateway gateway = mock(ContactMemoryLlmGateway.class);
        ContactMemoryConsolidationService consolidation = mock(ContactMemoryConsolidationService.class);
        ContactMemoryMutationService mutation = mock(ContactMemoryMutationService.class);
        ContactMemoryStateEntity state = state("RETRY_WAIT", 1);
        ContactMemoryModels.Context context = contextWithInbound("out");
        when(states.claim(eq(state.getId()), any(), any())).thenReturn(Optional.of(UUID.randomUUID()));
        when(contextService.load(OWNER_ID, CONTACT_ID, NOW)).thenReturn(context);
        when(gateway.generate(context)).thenThrow(
                new ContactMemoryLlmGateway.GatewayException("LLM_TIMEOUT", true));

        ContactMemoryWorker worker = worker(states, contextService, gateway, consolidation, mutation);

        worker.process(state, NOW);

        verify(states).fail(eq(state.getId()), any(), eq("LLM_TIMEOUT"), any(),
                eq(2), eq(NOW.plusSeconds(120)), eq(false));
    }

    @Test
    void llmFailureCreatesFailedAttemptWithDurationAndCursor() {
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryContextService contextService = mock(ContactMemoryContextService.class);
        ContactMemoryLlmGateway gateway = mock(ContactMemoryLlmGateway.class);
        ContactMemoryConsolidationService consolidation = mock(ContactMemoryConsolidationService.class);
        ContactMemoryMutationService mutation = mock(ContactMemoryMutationService.class);
        ContactMemoryAttemptService attempts = mock(ContactMemoryAttemptService.class);
        ContactMemoryStateEntity state = state("DIRTY", 0);
        ContactMemoryModels.Context context = contextWithInbound("out");
        ContactMemoryModels.AttemptRun run = new ContactMemoryModels.AttemptRun(
                UUID.randomUUID(), UUID.randomUUID(), NOW);
        when(states.claim(eq(state.getId()), any(), any())).thenReturn(Optional.of(UUID.randomUUID()));
        when(contextService.load(OWNER_ID, CONTACT_ID, NOW)).thenReturn(context);
        when(gateway.generate(context)).thenThrow(
                new ContactMemoryLlmGateway.GatewayException("LLM_TIMEOUT", true));

        ContactMemoryWorker worker = worker(states, contextService, gateway, consolidation, mutation, attempts);
        when(attempts.start(any(), any(), any(), anyInt(), any()))
                .thenReturn(run);
        worker.process(state, NOW);

        verify(attempts).fail(eq(run), eq("LLM_TIMEOUT"), any(), eq(1),
                eq(context.inputCursor()), eq(context.inboundMessages().size()), any());
    }

    @Test
    void nonRetryableOutputFailureBecomesTerminalWithoutAdvancingCursor() {
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryContextService contextService = mock(ContactMemoryContextService.class);
        ContactMemoryLlmGateway gateway = mock(ContactMemoryLlmGateway.class);
        ContactMemoryConsolidationService consolidation = mock(ContactMemoryConsolidationService.class);
        ContactMemoryMutationService mutation = mock(ContactMemoryMutationService.class);
        ContactMemoryStateEntity state = state("DIRTY", 2);
        ContactMemoryModels.Context context = contextWithInbound("out");
        when(states.claim(eq(state.getId()), any(), any())).thenReturn(Optional.of(UUID.randomUUID()));
        when(contextService.load(OWNER_ID, CONTACT_ID, NOW)).thenReturn(context);
        when(gateway.generate(context)).thenThrow(
                new ContactMemoryLlmGateway.GatewayException("INVALID_EVIDENCE", false));

        ContactMemoryWorker worker = worker(states, contextService, gateway, consolidation, mutation);

        worker.process(state, NOW);

        verify(states).fail(eq(state.getId()), any(), eq("INVALID_EVIDENCE"), any(),
                eq(3), eq(NOW), eq(true));
        verify(states, never()).complete(any(), any(), any(), any(), any());
    }

    @Test
    void staleLeaseCannotCommitAfterAnotherWorkerClaimsTheContact() {
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryContextService contextService = mock(ContactMemoryContextService.class);
        ContactMemoryLlmGateway gateway = mock(ContactMemoryLlmGateway.class);
        ContactMemoryConsolidationService consolidation = mock(ContactMemoryConsolidationService.class);
        ContactMemoryMutationService mutation = mock(ContactMemoryMutationService.class);
        ContactMemoryStateEntity state = state("DIRTY", 0);
        ContactMemoryModels.Context context = contextWithInbound("out");
        ContactMemoryModels.LlmOutput output = new ContactMemoryModels.LlmOutput(
                List.of(), null, List.of(), List.of(), "model", "{}");
        ContactMemoryModels.ConsolidationResult result = new ContactMemoryModels.ConsolidationResult(
                UUID.randomUUID(), null, "cursor", List.of(), List.of(), List.of(), null, "model");
        when(states.claim(eq(state.getId()), any(), any())).thenReturn(Optional.of(UUID.randomUUID()));
        when(contextService.load(OWNER_ID, CONTACT_ID, NOW)).thenReturn(context);
        when(gateway.generate(context)).thenReturn(output);
        when(consolidation.consolidate(eq(context), eq(output), any())).thenReturn(result);
        when(mutation.persist(eq(OWNER_ID), eq(CONTACT_ID), any(), eq(result), any()))
                .thenThrow(new ContactMemoryModels.ValidationException("LEASE_LOST"));

        ContactMemoryWorker worker = worker(states, contextService, gateway, consolidation, mutation);

        worker.process(state, NOW);

        verify(states, never()).complete(any(), any(), any(), any(), any());
        verify(states).fail(eq(state.getId()), any(), eq("LEASE_LOST"), any(),
                eq(1), eq(NOW.plusSeconds(60)), eq(false));
    }

    @Test
    void runOnceClaimsOnlyRunnableStatesWithinConfiguredBatch() {
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryContextService contextService = mock(ContactMemoryContextService.class);
        ContactMemoryLlmGateway gateway = mock(ContactMemoryLlmGateway.class);
        ContactMemoryConsolidationService consolidation = mock(ContactMemoryConsolidationService.class);
        ContactMemoryMutationService mutation = mock(ContactMemoryMutationService.class);
        when(states.listRunnable(NOW, null, 7)).thenReturn(List.of());

        int processed = worker(states, contextService, gateway, consolidation, mutation)
                .runOnce(NOW);

        assertThat(processed).isZero();
        verify(states).markStaleDirty(NOW, 7);
        verify(states).listRunnable(NOW, null, 7);
    }

    @Test
    void runOnceWithoutAClockInstantNeitherDiscoversNorClaims() {
        ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
        ContactMemoryWorker worker = worker(states, mock(ContactMemoryContextService.class),
                mock(ContactMemoryLlmGateway.class), mock(ContactMemoryConsolidationService.class),
                mock(ContactMemoryMutationService.class));

        assertThat(worker.runOnce(null)).isZero();
        verify(states, never()).markStaleDirty(any(), anyInt());
        verify(states, never()).listRunnable(any(), any(), anyInt());
    }

    private static ContactMemoryWorker worker(ContactMemoryStateMapper states,
                                              ContactMemoryContextService contextService,
                                              ContactMemoryLlmGateway gateway,
                                              ContactMemoryConsolidationService consolidation,
                                              ContactMemoryMutationService mutation) {
        return worker(states, contextService, gateway, consolidation, mutation,
                mock(ContactMemoryAttemptService.class));
    }

    private static ContactMemoryWorker worker(ContactMemoryStateMapper states,
                                              ContactMemoryContextService contextService,
                                              ContactMemoryLlmGateway gateway,
                                              ContactMemoryConsolidationService consolidation,
                                              ContactMemoryMutationService mutation,
                                              ContactMemoryAttemptService attempts) {
        when(attempts.start(any(), any(), any(), any(Integer.class), any()))
                .thenReturn(new ContactMemoryModels.AttemptRun(
                        UUID.randomUUID(), UUID.randomUUID(), NOW));
        return new ContactMemoryWorker(
                states, contextService, gateway, consolidation, mutation, attempts,
                new ContactMemoryConfig(50, 4000, 50000, 20, 1000, 10, 4000,
                        100, 100, 100, 500, 30, 7, 300, 3, 60, 900,
                        0, 0, "UTC"),
                "test-worker");
    }

    private static ContactMemoryStateEntity state(String status, int retryCount) {
        ContactMemoryStateEntity state = new ContactMemoryStateEntity();
        state.setId(UUID.randomUUID());
        state.setContactId(CONTACT_ID);
        state.setOwnerUserId(OWNER_ID);
        state.setStatus(status);
        state.setRetryCount(retryCount);
        state.setLastSuccessCursor("cursor");
        return state;
    }

    private static ContactMemoryModels.Context contextWithInbound(String... cursors) {
        List<com.crmforlogistics.messagecenter.entity.MessageEntity> messages = List.of();
        if (cursors.length > 0) {
            var message = new com.crmforlogistics.messagecenter.entity.MessageEntity();
            message.setId(UUID.randomUUID());
            message.setOccurredAt(NOW.minusSeconds(1));
            message.setBodyText("hello");
            messages = List.of(message);
        }
        return new ContactMemoryModels.Context(
                CONTACT_ID, OWNER_ID, messages, null, List.of(), List.of(), List.of(),
                new ContactMemoryModels.StableContext(null, List.of(), List.of(), List.of()),
                List.of(), List.of(), "cursor", cursors.length == 0 ? "cursor" : cursors[0]);
    }
}
