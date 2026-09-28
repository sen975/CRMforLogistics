package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryStateEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「帮我重算他的 AI 画像 / AI 标签」的服务侧判据。
 *
 * <h1>1. 「有没有东西可算」不在 Java 里判</h1>
 * 它写在 {@code markDirtyForRecompute} 的 SQL 里（与自动路径 {@code markStaleDirty} 同一条判据）。
 * 所以这里的断言是「什么时候<b>不</b>去写库」，以及「写的时候传的是什么」——
 * 真正的判据形状由 {@code ContactMemoryStateMapperSqlTest} 钉住，语义由
 * {@code ContactMemoryEndToEndTest} 在真库上钉住。
 *
 * <h1>2. 四种结果对应四句不同的话，所以不能合并成 boolean</h1>
 * 「已提交」「已经在队列里」「正在跑」「没有新内容所以不会变」对用户是四件事。
 * 合并之后最常见的错法是：把「没有新内容」报成「已提交」，用户等一周也不会看到变化。
 */
class ContactMemoryRecomputeServiceTest {

    private static final UUID OWNER = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID OTHER_OWNER = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000002");
    private static final UUID CONTACT = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-23T08:00:00Z");

    private final ContactMapper contacts = mock(ContactMapper.class);
    private final ContactMemoryStateMapper states = mock(ContactMemoryStateMapper.class);
    private final ContactMemoryRecomputeService service = new ContactMemoryRecomputeService(
            contacts, states, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aContactWhoseMemoryBelongsToSomeoneElseCannotBeRecomputed() {
        when(contacts.findByIdAndOwner(CONTACT, OWNER)).thenReturn(Optional.empty());

        assertThat(service.requestRecompute(OWNER, CONTACT)).isEmpty();
        verifyNoInteractions(states);
    }

    @Test
    void aMissingIdentityTouchesNothingAtAll() {
        assertThat(service.requestRecompute(null, CONTACT)).isEmpty();
        assertThat(service.requestRecompute(OWNER, null)).isEmpty();
        verifyNoInteractions(contacts);
        verifyNoInteractions(states);
    }

    @Test
    void aContactThatIsAlreadyQueuedIsNotSubmittedTwice() {
        givenMemoryOwner();
        for (String status : new String[]{"DIRTY", "RETRY_WAIT"}) {
            when(states.findByOwnerAndContact(OWNER, CONTACT)).thenReturn(Optional.of(state(status)));

            assertThat(service.requestRecompute(OWNER, CONTACT))
                    .as("已经在队列里：再标一次脏不会更快，只会让用户以为上一次没提交成功")
                    .contains(ContactMemoryRecomputeService.Outcome.ALREADY_PENDING);
        }
        verify(states, never()).markDirtyForRecompute(any(), any(), any());
    }

    @Test
    void aContactThatIsBeingProcessedIsReportedAsSuch() {
        givenMemoryOwner();
        when(states.findByOwnerAndContact(OWNER, CONTACT)).thenReturn(Optional.of(state("PROCESSING")));

        assertThat(service.requestRecompute(OWNER, CONTACT))
                .contains(ContactMemoryRecomputeService.Outcome.IN_PROGRESS);
        verify(states, never()).markDirtyForRecompute(any(), any(), any());
    }

    @Test
    void aFreshContactIsMarkedDirtyWithTheCallersIdentityAndTheClockInstant() {
        givenMemoryOwner();
        when(states.findByOwnerAndContact(OWNER, CONTACT)).thenReturn(Optional.empty());
        when(states.markDirtyForRecompute(OWNER, CONTACT, NOW)).thenReturn(1);

        assertThat(service.requestRecompute(OWNER, CONTACT))
                .contains(ContactMemoryRecomputeService.Outcome.SUBMITTED);
        // 这里传的 Instant 只是 SQL 里 `m.received_at <= now` 的那个上界；
        // 写进 last_inbound_at 的时间由 SQL 自己从消息表里取 —— 见 mapper 的方法注释。
        verify(states).markDirtyForRecompute(OWNER, CONTACT, NOW);
    }

    @Test
    void whenNothingIsNewTheStateIsLeftExactlyAsItWas() {
        givenMemoryOwner();
        when(states.findByOwnerAndContact(OWNER, CONTACT)).thenReturn(Optional.of(state("CLEAN")));
        when(states.markDirtyForRecompute(OWNER, CONTACT, NOW)).thenReturn(0);

        assertThat(service.requestRecompute(OWNER, CONTACT))
                .as("影响 0 行 = 没有比游标更新的入站消息；这件事必须说成「不会变化」而不是「已提交」")
                .contains(ContactMemoryRecomputeService.Outcome.NOTHING_NEW);
        verify(states, never()).markDirty(any(), any(), any());
    }

    @Test
    void aFailedRunCanBeRetriedByHandEvenThoughTheAutomaticPathWouldNot() {
        givenMemoryOwner();
        when(states.findByOwnerAndContact(OWNER, CONTACT)).thenReturn(Optional.of(state("FAILED")));
        when(states.markDirtyForRecompute(OWNER, CONTACT, NOW)).thenReturn(1);

        assertThat(service.requestRecompute(OWNER, CONTACT))
                .as("终态失败正是「人主动要求再来一次」的用武之地（自动路径刻意不重试它）")
                .contains(ContactMemoryRecomputeService.Outcome.SUBMITTED);
    }

    @Test
    void theOtherOwnerNeverEvenReachesTheStateTable() {
        when(contacts.findByIdAndOwner(CONTACT, OTHER_OWNER)).thenReturn(Optional.empty());

        assertThat(service.requestRecompute(OTHER_OWNER, CONTACT)).isEmpty();
        verify(states, never()).findByOwnerAndContact(any(), any());
        verify(states, never()).markDirtyForRecompute(any(), any(), any());
    }

    // ---------- 夹具 ----------

    private void givenMemoryOwner() {
        when(contacts.findByIdAndOwner(CONTACT, OWNER)).thenReturn(Optional.of(mock(ContactEntity.class)));
    }

    private static ContactMemoryStateEntity state(String status) {
        ContactMemoryStateEntity state = new ContactMemoryStateEntity();
        state.setStatus(status);
        return state;
    }
}
