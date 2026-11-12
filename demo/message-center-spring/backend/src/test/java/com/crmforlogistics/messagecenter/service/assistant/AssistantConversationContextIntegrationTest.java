package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.entity.AssistantConversationMessageEntity;
import com.crmforlogistics.messagecenter.mapper.AssistantConversationMessageMapper;
import com.crmforlogistics.messagecenter.mapper.AssistantConversationSummaryMapper;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolRegistry;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssistantConversationContextIntegrationTest {

    @Test
    void summarizedPrefixEntersPromptWhileReplayReturnsOnlyOriginalTranscript() {
        UUID user = AssistantFixtures.USER;
        UUID conversation = AssistantFixtures.CONVERSATION;
        AssistantConversationMessageMapper messageMapper = mock(AssistantConversationMessageMapper.class);
        AssistantConversationSummaryMapper summaryMapper = mock(AssistantConversationSummaryMapper.class);
        List<AssistantConversationMessageEntity> transcript = List.of(
                row(user, conversation, 1, "最初的目标是确认报价"),
                row(user, conversation, 2, "用户确认周五联系"),
                row(user, conversation, 3, "请再核对最新报价"),
                row(user, conversation, 4, "我会先查当前记录"));
        when(messageMapper.listRecentByConversation(user, conversation, 2))
                .thenReturn(List.of(transcript.get(3), transcript.get(2)));
        when(messageMapper.listAfter(eq(user), eq(conversation), isNull(), isNull(), eq(100)))
                .thenReturn(transcript);
        when(messageMapper.countBefore(eq(user), eq(conversation), any(Instant.class), any(UUID.class)))
                .thenReturn(2);
        when(messageMapper.listByConversation(user, conversation, 200)).thenReturn(transcript);
        when(summaryMapper.find(user, conversation)).thenReturn(null);
        when(summaryMapper.insertIfAbsent(eq(user), eq(conversation), anyString(), any(Instant.class), any(UUID.class), eq(2)))
                .thenReturn(1);

        AssistantConversationLogService log = new AssistantConversationLogService(messageMapper);
        AssistantConversationSummarizer summarizer = mock(AssistantConversationSummarizer.class);
        when(summarizer.summarize(isNull(), anyList(), eq(1)))
                .thenReturn("目标是确认报价，约定周五联系");
        ToolRegistry registry = AssistantFixtures.registry(new TodoItemService(mock(
                com.crmforlogistics.messagecenter.mapper.TodoItemMapper.class)));
        AssistantPromptBuilder promptBuilder = new AssistantPromptBuilder(
                registry, AssistantFixtures.config(), AssistantFixtures.objectMapper());
        AssistantConversationContextService context = new AssistantConversationContextService(
                summaryMapper, messageMapper, summarizer, new AssistantTokenEstimator("o200k_base"),
                promptBuilder, 2, 100, 0, 1, 8192);

        List<AssistantConversationMessageEntity> recentRows = log.recentRowsForPrompt(user, conversation, 2);
        List<AssistantMessage> recent = log.recentForPrompt(user, conversation, 2);
        AssistantConversationContextService.PreparedContext prepared = context.prepare(
                user, conversation, recentRows, recent, "继续跟进", AssistantFixtures.context());
        List<Map<String, String>> request = promptBuilder.buildMessages(
                AssistantFixtures.context(), prepared.summary(), prepared.recentHistory(), "继续跟进");
        String rendered = request.stream().map(message -> message.get("content")).collect(Collectors.joining("\n"));

        assertThat(rendered).contains("目标是确认报价，约定周五联系")
                .contains("请再核对最新报价")
                .contains("我会先查当前记录")
                .contains("<<<HISTORICAL SUMMARY>>>")
                .doesNotContain("最初的目标是确认报价");
        assertThat(log.replay(user, conversation, 200)).extracting(AssistantConversationLogService.Message::text)
                .containsExactly("最初的目标是确认报价", "用户确认周五联系", "请再核对最新报价", "我会先查当前记录");
    }

    private static AssistantConversationMessageEntity row(UUID user, UUID conversation, int number, String text) {
        AssistantConversationMessageEntity row = new AssistantConversationMessageEntity();
        row.setId(new UUID(0, number));
        row.setUserId(user);
        row.setConversationId(conversation);
        row.setRole(number % 2 == 0 ? "assistant" : "user");
        row.setKind(number % 2 == 0 ? "ANSWER" : null);
        row.setText(text);
        row.setCreatedAt(Instant.parse("2026-09-23T00:00:0" + number + "Z"));
        return row;
    }
}
