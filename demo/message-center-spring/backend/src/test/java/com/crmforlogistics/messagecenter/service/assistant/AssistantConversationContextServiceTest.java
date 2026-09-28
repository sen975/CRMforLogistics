package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.entity.AssistantConversationMessageEntity;
import com.crmforlogistics.messagecenter.mapper.AssistantConversationMessageMapper;
import com.crmforlogistics.messagecenter.mapper.AssistantConversationSummaryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class AssistantConversationContextServiceTest {

    @Test
    void shortHistoryDoesNotCallSummarizerAndKeepsChronologicalRecentMessages() {
        AssistantConversationSummaryMapper summaries = mock(AssistantConversationSummaryMapper.class);
        AssistantConversationMessageMapper messages = mock(AssistantConversationMessageMapper.class);
        AssistantConversationSummarizer summarizer = mock(AssistantConversationSummarizer.class);
        AssistantConversationContextService service = new AssistantConversationContextService(
                summaries, messages, summarizer, new AssistantTokenEstimator("o200k_base"), 2, 100, 1, 100);

        AssistantConversationContextService.PreparedContext result = service.prepare(
                UUID.randomUUID(), UUID.randomUUID(),
                List.of(row("one", 1), row("two", 2)),
                List.of(AssistantMessage.user("one"), AssistantMessage.assistant("two")), "now", null);

        assertThat(result.summary()).isNull();
        assertThat(result.recentHistory()).extracting(AssistantMessage::text).containsExactly("one", "two");
        verifyNoInteractions(summarizer);
    }

    @Test
    void compactsOlderPrefixOnceAndLeavesRecentWindowVerbatim() {
        UUID user = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        AssistantConversationSummaryMapper summaries = mock(AssistantConversationSummaryMapper.class);
        AssistantConversationMessageMapper messages = mock(AssistantConversationMessageMapper.class);
        AssistantConversationSummarizer summarizer = mock(AssistantConversationSummarizer.class);
        when(summaries.find(user, conversation)).thenReturn(null);
        when(messages.listAfter(eq(user), eq(conversation), isNull(), isNull(), eq(100)))
                .thenReturn(List.of(row("old one", 1), row("old answer", 2), row("recent", 3)));
        when(summaries.insertIfAbsent(eq(user), eq(conversation), any(), any(Instant.class), any(UUID.class), eq(1)))
                .thenReturn(1);
        when(summarizer.summarize(isNull(), any(), eq(1))).thenReturn("目标：回访");
        AssistantConversationContextService service = new AssistantConversationContextService(
                summaries, messages, summarizer, new AssistantTokenEstimator("o200k_base"), 2, 100, 1, 1);
        when(messages.countBefore(eq(user), eq(conversation), any(Instant.class), any(UUID.class))).thenReturn(1);

        AssistantConversationContextService.PreparedContext result = service.prepare(
                user, conversation, List.of(row("old answer", 2), row("recent", 3)),
                List.of(AssistantMessage.assistant("old answer"), AssistantMessage.user("recent")), "now", null);

        assertThat(result.summary()).isEqualTo("目标：回访");
        assertThat(result.recentHistory()).extracting(AssistantMessage::text).containsExactly("old answer", "recent");
        assertThat(result.summarizedMessages()).isEqualTo(1);
        verify(summaries).insertIfAbsent(eq(user), eq(conversation), eq("目标：回访"), any(Instant.class), any(UUID.class), eq(1));
    }

    @Test
    void summaryFailureDoesNotAdvanceCursorAndReportsUnrepresentedRows() {
        UUID user = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        AssistantConversationSummaryMapper summaries = mock(AssistantConversationSummaryMapper.class);
        AssistantConversationMessageMapper messages = mock(AssistantConversationMessageMapper.class);
        AssistantConversationSummarizer summarizer = mock(AssistantConversationSummarizer.class);
        List<AssistantConversationMessageEntity> page = List.of(row("old message long enough", 1), row("recent", 2));
        when(messages.listAfter(eq(user), eq(conversation), isNull(), isNull(), eq(1))).thenReturn(page.subList(0, 1));
        when(messages.countBefore(eq(user), eq(conversation), any(Instant.class), any(UUID.class))).thenReturn(1);
        when(summarizer.summarize(isNull(), any(), eq(1)))
                .thenThrow(new AssistantConversationSummarizer.SummaryFailure("provider timeout"));
        AssistantConversationContextService service = new AssistantConversationContextService(
                summaries, messages, summarizer, new AssistantTokenEstimator("o200k_base"), 2, 1, 0, 1);

        AssistantConversationContextService.PreparedContext result = service.prepare(
                user, conversation, page.subList(1, 2), List.of(AssistantMessage.user("recent")), "now", null);

        assertThat(result.summary()).isNull();
        assertThat(result.droppedMessages()).isEqualTo(1);
        verify(summaries, never()).insertIfAbsent(any(), any(), any(), any(), any(), anyInt());
        verify(summaries, never()).advanceIfVersion(any(), any(), anyLong(), any(), any(), any(), anyInt());
    }

    @Test
    void projectionReadFailureKeepsRecentRawHistoryWithoutCallingSummarizer() {
        UUID user = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        AssistantConversationSummaryMapper summaries = mock(AssistantConversationSummaryMapper.class);
        AssistantConversationMessageMapper messages = mock(AssistantConversationMessageMapper.class);
        AssistantConversationSummarizer summarizer = mock(AssistantConversationSummarizer.class);
        when(summaries.find(user, conversation)).thenThrow(new DataAccessResourceFailureException("projection unavailable"));
        when(messages.countBefore(eq(user), eq(conversation), any(Instant.class), any(UUID.class))).thenReturn(3);
        AssistantConversationContextService service = new AssistantConversationContextService(
                summaries, messages, summarizer, new AssistantTokenEstimator("o200k_base"), 2, 100, 1, 100);

        AssistantConversationContextService.PreparedContext result = service.prepare(
                user, conversation, List.of(row("recent", 2)),
                List.of(AssistantMessage.assistant("recent")), "now", null);

        assertThat(result.summary()).isNull();
        assertThat(result.recentHistory()).extracting(AssistantMessage::text).containsExactly("recent");
        assertThat(result.droppedMessages()).isEqualTo(3);
        verifyNoInteractions(summarizer);
    }

    @Test
    void projectionPageFailureKeepsRecentRawHistoryWithoutAdvancingCursor() {
        UUID user = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        AssistantConversationSummaryMapper summaries = mock(AssistantConversationSummaryMapper.class);
        AssistantConversationMessageMapper messages = mock(AssistantConversationMessageMapper.class);
        AssistantConversationSummarizer summarizer = mock(AssistantConversationSummarizer.class);
        when(messages.listAfter(eq(user), eq(conversation), isNull(), isNull(), eq(100)))
                .thenThrow(new DataAccessResourceFailureException("projection page unavailable"));
        when(messages.countBefore(eq(user), eq(conversation), any(Instant.class), any(UUID.class))).thenReturn(2);
        AssistantConversationContextService service = new AssistantConversationContextService(
                summaries, messages, summarizer, new AssistantTokenEstimator("o200k_base"), 2, 100, 1, 100);

        AssistantConversationContextService.PreparedContext result = service.prepare(
                user, conversation, List.of(row("recent", 2)),
                List.of(AssistantMessage.assistant("recent")), "now", null);

        assertThat(result.summary()).isNull();
        assertThat(result.recentHistory()).extracting(AssistantMessage::text).containsExactly("recent");
        assertThat(result.droppedMessages()).isEqualTo(2);
        verifyNoInteractions(summarizer);
        verify(summaries, never()).insertIfAbsent(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void readsOnlyOneBoundedSourcePagePerRequest() {
        UUID user = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        AssistantConversationSummaryMapper summaries = mock(AssistantConversationSummaryMapper.class);
        AssistantConversationMessageMapper messages = mock(AssistantConversationMessageMapper.class);
        AssistantConversationSummarizer summarizer = mock(AssistantConversationSummarizer.class);
        List<AssistantConversationMessageEntity> page = List.of(
                row("older-1 sufficiently long", 1), row("older-2 sufficiently long", 2));
        when(messages.listAfter(eq(user), eq(conversation), isNull(), isNull(), eq(2))).thenReturn(page);
        when(messages.countBefore(eq(user), eq(conversation), any(Instant.class), any(UUID.class))).thenReturn(3);
        when(summaries.insertIfAbsent(any(), any(), any(), any(), any(), anyInt())).thenReturn(1);
        when(summarizer.summarize(isNull(), any(), eq(1))).thenReturn("摘要");
        AssistantConversationContextService service = new AssistantConversationContextService(
                summaries, messages, summarizer, new AssistantTokenEstimator("o200k_base"), 2, 2, 0, 1);
        List<AssistantConversationMessageEntity> recentRows = List.of(row("recent-1", 3));

        AssistantConversationContextService.PreparedContext result = service.prepare(
                user, conversation, recentRows, List.of(AssistantMessage.user("recent-1")), "now", null);

        verify(messages, times(1)).listAfter(eq(user), eq(conversation), isNull(), isNull(), eq(2));
        verify(summarizer, times(1)).summarize(isNull(), argThat(delta -> delta.size() == 2), eq(1));
        assertThat(result.summarizedMessages()).isEqualTo(2);
        assertThat(result.droppedMessages()).isEqualTo(1);
    }

    @Test
    void dropsRawHistoryThenSummaryWhenFullRenderedPromptExceedsBudget() {
        UUID user = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        AssistantConversationSummaryMapper summaries = mock(AssistantConversationSummaryMapper.class);
        AssistantConversationMessageMapper messages = mock(AssistantConversationMessageMapper.class);
        AssistantConversationSummarizer summarizer = mock(AssistantConversationSummarizer.class);
        AssistantTokenEstimator estimator = new AssistantTokenEstimator("o200k_base");
        AssistantPromptBuilder prompt = new AssistantPromptBuilder(
                AssistantFixtures.registry(new TodoItemService(mock(
                        com.crmforlogistics.messagecenter.mapper.TodoItemMapper.class))),
                AssistantFixtures.config(), AssistantFixtures.objectMapper());
        AssistantContext assistantContext = AssistantFixtures.context();
        int baseline = prompt.buildMessages(assistantContext, null, List.of(), "now").stream()
                .mapToInt(message -> estimator.count(message.get("role"))
                        + estimator.count(message.get("content")) + 4).sum() + 3;
        AssistantConversationMessageEntity old = row("old transcript", 1);
        AssistantConversationMessageEntity recentRow = row("recent transcript", 2);
        when(messages.listAfter(eq(user), eq(conversation), isNull(), isNull(), eq(100)))
                .thenReturn(List.of(old, recentRow));
        when(messages.countBefore(eq(user), eq(conversation), any(Instant.class), any(UUID.class))).thenReturn(1);
        when(summaries.insertIfAbsent(any(), any(), any(), any(), any(), anyInt())).thenReturn(1);
        when(summarizer.summarize(isNull(), any(), eq(256))).thenReturn("summary that must be omitted when the budget is tight");
        AssistantConversationContextService service = new AssistantConversationContextService(
                summaries, messages, summarizer, estimator, prompt, 8, 100, 0, 256, baseline + 4);

        AssistantConversationContextService.PreparedContext result = service.prepare(
                user, conversation, List.of(recentRow), List.of(AssistantMessage.user("recent transcript")),
                "now", assistantContext);

        assertThat(result.summary()).isNull();
        assertThat(result.recentHistory()).isEmpty();
        assertThat(result.droppedMessages()).isEqualTo(2);
        assertThat(service.withinBudget(prompt.buildMessages(
                assistantContext, result.summary(), result.recentHistory(), "now"))).isTrue();
    }

    private static AssistantConversationMessageEntity row(String text, int second) {
        AssistantConversationMessageEntity row = new AssistantConversationMessageEntity();
        row.setId(new UUID(0, second));
        row.setUserId(new UUID(0, 1));
        row.setConversationId(new UUID(0, 2));
        row.setRole(second % 2 == 0 ? "assistant" : "user");
        row.setText(text);
        row.setCreatedAt(Instant.parse("2026-09-23T00:00:0" + second + "Z"));
        return row;
    }
}
