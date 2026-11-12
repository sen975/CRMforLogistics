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

    // ---------- 装配下一轮语境：最近若干条，正序 ----------

    /**
     * 必须读**最近**的若干条，而不是最早的。
     *
     * <p>断言的是「调用了哪个 mapper 方法」，因为两者返回的形状完全一样（都是
     * {@code List<Entity>}），只有在会话变长之后才看得出差别 —— 而那已经是生产环境了。
     * 用错方法拿到的是这段对话的**开头**，把「刚说过的话」全部丢掉，
     * 而指代（「那条」「改成后天」）恰恰指向刚说过的话。
     */
    @Test
    void recentForPromptReadsTheLatestEntriesNotTheEarliest() {
        when(mapper.listRecentByConversation(eq(USER), eq(CONVERSATION), anyInt())).thenReturn(List.of());

        service.recentForPrompt(USER, CONVERSATION, 8);

        verify(mapper).listRecentByConversation(USER, CONVERSATION, 8);
        verify(mapper, never()).listByConversation(any(), any(), anyInt());
    }

    /**
     * mapper 按「最新在前」返回，提示词要「旧在前」—— 这个反转必须发生。
     *
     * <p>漏掉它的后果不是少一条消息，而是**整段对话的顺序反过来**：模型会看到助手先答、
     * 用户后问，指代与因果全错。而这个错误在界面上完全看不出来 —— 提示词是不可见的。
     */
    @Test
    void recentForPromptRestoresChronologicalOrderForThePrompt() {
        when(mapper.listRecentByConversation(eq(USER), eq(CONVERSATION), anyInt())).thenReturn(List.of(
                row("assistant", "安排在什么时间？"),
                row("user", "帮我建个待办")));

        List<AssistantMessage> messages = service.recentForPrompt(USER, CONVERSATION, 8);

        assertEquals(2, messages.size());
        assertEquals("帮我建个待办", messages.get(0).text());
        assertEquals(AssistantMessage.Role.USER, messages.get(0).role());
        assertEquals("安排在什么时间？", messages.get(1).text());
        assertEquals(AssistantMessage.Role.ASSISTANT, messages.get(1).role());
    }

    @Test
    void recentForPromptWithoutIdentityReadsNothing() {
        assertTrue(service.recentForPrompt(null, CONVERSATION, 8).isEmpty());
        assertTrue(service.recentForPrompt(USER, null, 8).isEmpty());

        verify(mapper, never()).listRecentByConversation(any(), any(), anyInt());
    }

    @Test
    void recentForPromptClampsTheLimitIntoRange() {
        when(mapper.listRecentByConversation(eq(USER), eq(CONVERSATION), anyInt())).thenReturn(List.of());

        service.recentForPrompt(USER, CONVERSATION, 0);
        service.recentForPrompt(USER, CONVERSATION, 10_000);

        verify(mapper).listRecentByConversation(USER, CONVERSATION, 1);
        verify(mapper).listRecentByConversation(USER, CONVERSATION, 200);
    }

    // ---------- 最近会话号：浏览器忘了它时的那一条路 ----------

    /**
     * 归属只由 {@code userId} 决定，方法上没有第二个筛选项。
     *
     * <p>刻意断言「问的是这个用户」。若哪天有人给它加上「按会话号再筛一下」之类的参数，
     * 「我上次在哪个会话里」就可能在换设备后给出另一个答案 —— 而那时前端会拿它当成
     * 用户的同一段对话来接。
     */
    @Test
    void latestConversationIdAsksForTheUsersOwnRow() {
        when(mapper.latestConversationId(USER)).thenReturn(CONVERSATION);

        assertEquals(CONVERSATION, service.latestConversationId(USER));

        verify(mapper).latestConversationId(USER);
    }

    /**
     * 「没有」不是错误：这个用户还没有任何对话。
     *
     * <p>它与「查不到是因为出错了」必须给出同一个返回值（{@code null}），
     * 因为调用方对两者的处置完全相同 —— 安静地开一段新对话。
     */
    @Test
    void latestConversationIdIsNullWhenThereIsNothingToResume() {
        when(mapper.latestConversationId(USER)).thenReturn(null);

        assertNull(service.latestConversationId(USER));
    }

    @Test
    void latestConversationIdWithoutIdentityReadsNothing() {
        assertNull(service.latestConversationId(null));

        verify(mapper, never()).latestConversationId(any());
    }

    private static AssistantConversationMessageEntity row(String role, String text) {
        AssistantConversationMessageEntity entity = new AssistantConversationMessageEntity();
        entity.setRole(role);
        entity.setText(text);
        return entity;
    }
}
