package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.entity.ContactMemoryAttemptEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContactMemoryAttemptServiceTest {
    private static final UUID CONTACT_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final Instant STARTED_AT = Instant.parse("2026-09-15T00:00:00Z");

    @Test
    void startCreatesStableAttemptRunBeforeLlmInvocation() {
        ContactMemoryMapper mapper = mock(ContactMemoryMapper.class);
        when(mapper.insertAttempt(any(), eq(CONTACT_ID), eq(OWNER_ID))).thenReturn(1);

        ContactMemoryModels.AttemptRun run = new ContactMemoryAttemptService(mapper)
                .start(OWNER_ID, CONTACT_ID, "cursor", 2, STARTED_AT);

        assertThat(run.attemptId()).isNotNull();
        assertThat(run.generationBatchId()).isNotNull();
        assertThat(run.startedAt()).isEqualTo(STARTED_AT);
        verify(mapper).insertAttempt(any(ContactMemoryAttemptEntity.class),
                eq(CONTACT_ID), eq(OWNER_ID));
    }

    @Test
    void failedAttemptKeepsInputCursorAndNeverInventsOutputCursor() {
        ContactMemoryMapper mapper = mock(ContactMemoryMapper.class);
        when(mapper.updateAttemptFailed(any(), any(), any(), anyInt(), any(), anyInt(), anyLong(), any()))
                .thenReturn(1);
        ContactMemoryModels.AttemptRun run = new ContactMemoryModels.AttemptRun(
                UUID.randomUUID(), UUID.randomUUID(), STARTED_AT);

        new ContactMemoryAttemptService(mapper).fail(
                run, "LLM_TIMEOUT", "provider timeout", 2,
                "input-cursor", 3, STARTED_AT.plusSeconds(4));

        verify(mapper).updateAttemptFailed(eq(run.attemptId()), eq("LLM_TIMEOUT"),
                eq("provider timeout"), eq(2), eq("input-cursor"), eq(3),
                eq(4000L), eq(STARTED_AT.plusSeconds(4)));
    }
}
