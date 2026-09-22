package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.entity.AssistantConversationMessageEntity;
import com.crmforlogistics.messagecenter.mapper.AssistantConversationMessageMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话落地的边界 —— 这里每一条都对应一个刻意的设计决定，而不是「覆盖率」。
 */
class AssistantConversationLogServiceTest {

    private static final UUID USER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CONVERSATION = UUID.fromString("20000000-0000-0000-0000-000000000002");

    private final AssistantConversationMessageMapper mapper = mock(AssistantConversationMessageMapper.class);
    private final AssistantConversationLogService service = new AssistantConversationLogService(mapper);

    @Test
    void recordsBothSidesOfATurnWithTheAssistantsOutcome() {
        service.appendUser(USER, CONVERSATION, "把张总那条标记完成");
        service.appendAssistant(USER, CONVERSATION, AssistantTurnResult.Kind.EXECUTED, "已完成");

        ArgumentCaptor<AssistantConversationMessageEntity> captor =
                ArgumentCaptor.forClass(AssistantConversationMessageEntity.class);
        verify(mapper, times(2)).insert(captor.capture());

        List<AssistantConversationMessageEntity> rows = captor.getAllValues();
        assertEquals("user", rows.get(0).getRole());
        // 用户说的话没有「终点」这回事。数据库里也有同样的 CHECK 约束兜着。
        assertNull(rows.get(0).getKind());
        assertEquals("assistant", rows.get(1).getRole());
        assertEquals("EXECUTED", rows.get(1).getKind());
        assertEquals(USER, rows.get(1).getUserId());
        assertEquals(CONVERSATION, rows.get(1).getConversationId());
    }

    @Test
    void writesNothingWithoutAConversationId() {
        // 没有会话号时写入，会把不同对话混进同一个 NULL 分组，
        // 将来回放出来是一段杂糅的、看起来像真的的历史 —— 比没有历史更糟。
        service.appendUser(USER, null, "你好");
        service.appendAssistant(USER, null, AssistantTurnResult.Kind.ANSWER, "你好");

        verify(mapper, never()).insert(any());
    }

    @Test
    void skipsBlankText() {
        service.appendAssistant(USER, CONVERSATION, AssistantTurnResult.Kind.ANSWER, "   ");

        verify(mapper, never()).insert(any());
    }

    @Test
    void aFailedWriteNeverBreaksTheTurn() {
        // 工具可能已经执行成功。此时因为「日志写不进去」而让这一轮失败，
        // 比少一条记录严重得多 —— 用户会以为没生效，转而去重做一次已经做过的操作。
        when(mapper.insert(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.appendUser(USER, CONVERSATION, "你好"));
    }

    @Test
    void replayWithoutIdentityReadsNothing() {
        assertTrue(service.replay(null, CONVERSATION, 10).isEmpty());
        assertTrue(service.replay(USER, null, 10).isEmpty());

        verify(mapper, never()).listByConversation(any(), any(), anyInt());
    }

    @Test
    void replayClampsTheLimitIntoRange() {
        when(mapper.listByConversation(eq(USER), eq(CONVERSATION), anyInt())).thenReturn(List.of());

        service.replay(USER, CONVERSATION, 0);
        service.replay(USER, CONVERSATION, 10_000);

        verify(mapper).listByConversation(USER, CONVERSATION, 1);
        verify(mapper).listByConversation(USER, CONVERSATION, 200);
    }

    @Test
    void replayCarriesRoleKindTextAndTime() {
        AssistantConversationMessageEntity row = new AssistantConversationMessageEntity();
        row.setRole("assistant");
        row.setKind("QUESTION");
        row.setText("这条待办安排在哪一天？");
        row.setCreatedAt(Instant.parse("2026-09-22T01:00:00Z"));
        when(mapper.listByConversation(eq(USER), eq(CONVERSATION), anyInt())).thenReturn(List.of(row));

        List<AssistantConversationLogService.Message> messages = service.replay(USER, CONVERSATION, 20);

        assertEquals(1, messages.size());
        assertEquals("assistant", messages.get(0).role());
        assertEquals("QUESTION", messages.get(0).kind());
        assertEquals("这条待办安排在哪一天？", messages.get(0).text());
        assertEquals(Instant.parse("2026-09-22T01:00:00Z"), messages.get(0).createdAt());
    }
}
