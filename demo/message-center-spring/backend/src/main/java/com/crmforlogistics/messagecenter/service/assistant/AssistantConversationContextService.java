package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.entity.AssistantConversationMessageEntity;
import com.crmforlogistics.messagecenter.entity.AssistantConversationSummaryEntity;
import com.crmforlogistics.messagecenter.mapper.AssistantConversationMessageMapper;
import com.crmforlogistics.messagecenter.mapper.AssistantConversationSummaryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import com.crmforlogistics.messagecenter.config.ConditionalOnAssistantEnabled;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Owns the rolling historical projection and keeps recent persisted turns verbatim. */
@Component
@ConditionalOnAssistantEnabled
public class AssistantConversationContextService {

    private static final Logger log = LoggerFactory.getLogger(AssistantConversationContextService.class);

    private final AssistantConversationSummaryMapper summaries;
    private final AssistantConversationMessageMapper messages;
    private final AssistantConversationSummarizer summarizer;
    private final AssistantTokenEstimator tokens;
    private final AssistantPromptBuilder promptBuilder;
    private final int recentLimit;
    private final int sourceBatchSize;
    private final int minimumSavingsTokens;
    private final int summaryOutputTokens;
    private final int maxInputTokens;

    @Autowired
    public AssistantConversationContextService(AssistantConversationSummaryMapper summaries,
                                              AssistantConversationMessageMapper messages,
                                              AssistantConversationSummarizer summarizer,
                                              AssistantTokenEstimator tokens,
                                              AssistantPromptBuilder promptBuilder,
                                              @Value("${assistant.max-history-turns:8}") int recentLimit,
                                              @Value("${assistant.compaction.source-batch-size:100}") int sourceBatchSize,
                                              @Value("${assistant.compaction.minimum-savings-tokens:64}") int minimumSavingsTokens,
                                              @Value("${assistant.compaction.summary-output-tokens:256}") int summaryOutputTokens,
                                              @Value("${assistant.compaction.max-input-tokens:24576}") int maxInputTokens) {
        validateBounds(recentLimit, sourceBatchSize, minimumSavingsTokens, summaryOutputTokens, maxInputTokens);
        this.summaries = summaries;
        this.messages = messages;
        this.summarizer = summarizer;
        this.tokens = tokens;
        this.promptBuilder = promptBuilder;
        this.recentLimit = recentLimit;
        this.sourceBatchSize = sourceBatchSize;
        this.minimumSavingsTokens = minimumSavingsTokens;
        this.summaryOutputTokens = summaryOutputTokens;
        this.maxInputTokens = maxInputTokens;
    }

    AssistantConversationContextService(AssistantConversationSummaryMapper summaries,
                                        AssistantConversationMessageMapper messages,
                                        AssistantConversationSummarizer summarizer,
                                        AssistantTokenEstimator tokens,
                                        int recentLimit, int sourceBatchSize,
                                        int minimumSavingsTokens, int summaryOutputTokens) {
        validateBounds(recentLimit, sourceBatchSize, minimumSavingsTokens, summaryOutputTokens, Integer.MAX_VALUE);
        this.summaries = summaries;
        this.messages = messages;
        this.summarizer = summarizer;
        this.tokens = tokens;
        this.promptBuilder = null;
        this.recentLimit = recentLimit;
        this.sourceBatchSize = sourceBatchSize;
        this.minimumSavingsTokens = minimumSavingsTokens;
        this.summaryOutputTokens = summaryOutputTokens;
        this.maxInputTokens = Integer.MAX_VALUE;
    }

    private static void validateBounds(int recentLimit, int sourceBatchSize,
                                       int minimumSavingsTokens, int summaryOutputTokens, int maxInputTokens) {
        if (recentLimit < 0 || sourceBatchSize <= 0 || minimumSavingsTokens < 0
                || summaryOutputTokens <= 0 || maxInputTokens <= 0) {
            throw new IllegalArgumentException("invalid assistant context compaction bounds");
        }
    }

    public PreparedContext prepare(UUID userId, UUID conversationId,
                                   List<AssistantConversationMessageEntity> recentPersistedRows,
                                   List<AssistantMessage> guardedRecentHistory,
                                   String currentText, AssistantContext context) {
        List<AssistantMessage> recent = guardedRecentHistory == null ? List.of() : List.copyOf(guardedRecentHistory);
        if (recentPersistedRows != null && recentPersistedRows.size() > recentLimit) {
            recentPersistedRows = recentPersistedRows.subList(recentPersistedRows.size() - recentLimit,
                    recentPersistedRows.size());
        }
        if (recent.size() > recentLimit) {
            recent = recent.subList(recent.size() - recentLimit, recent.size());
        }
        if (recentPersistedRows != null && recentPersistedRows.size() > recent.size()) {
            recentPersistedRows = recentPersistedRows.subList(recentPersistedRows.size() - recent.size(),
                    recentPersistedRows.size());
        }
        if (userId == null || conversationId == null || recentPersistedRows == null || recentPersistedRows.isEmpty()) {
            return new PreparedContext(null, recent, 0, 0);
        }

        AssistantConversationMessageEntity anchor = recentPersistedRows.get(0);
        try {
            return fitBudget(project(userId, conversationId, anchor, recentPersistedRows, recent),
                    context, currentText);
        } catch (DataAccessException failure) {
            log.warn("event=assistant.context_projection_unavailable conversationId={} diagnostic=DATA_ACCESS",
                    conversationId, failure);
            int dropped = 0;
            try {
                dropped = messages.countBefore(userId, conversationId, anchor.getCreatedAt(), anchor.getId());
            } catch (DataAccessException countFailure) {
                log.warn("event=assistant.context_omitted_count_unavailable conversationId={} diagnostic=DATA_ACCESS",
                        conversationId, countFailure);
            }
            return fitBudget(new PreparedContext(null, recent, dropped, 0), context, currentText);
        }
    }

    private PreparedContext project(UUID userId, UUID conversationId,
                                    AssistantConversationMessageEntity anchor,
                                    List<AssistantConversationMessageEntity> recentPersistedRows,
                                    List<AssistantMessage> recent) {
        AssistantConversationSummaryEntity existing = summaries.find(userId, conversationId);
        if (existing != null && compare(existing.getThroughCreatedAt(), existing.getThroughMessageId(),
                anchor.getCreatedAt(), anchor.getId()) >= 0) {
            List<AssistantMessage> nonDuplicatedRecent = recentPersistedRows.stream()
                    .filter(row -> compare(row.getCreatedAt(), row.getId(), existing.getThroughCreatedAt(),
                            existing.getThroughMessageId()) > 0)
                    .map(row -> new AssistantMessage(AssistantMessage.Role.fromWire(row.getRole()), row.getText()))
                    .toList();
            return new PreparedContext(existing.getSummary(), nonDuplicatedRecent,
                    Math.max(0, messages.countBefore(userId, conversationId,
                            anchor.getCreatedAt(), anchor.getId()) - existing.getCoveredMessageCount()),
                    existing.getCoveredMessageCount());
        }
        Instant afterCreatedAt = existing == null ? null : existing.getThroughCreatedAt();
        UUID afterMessageId = existing == null ? null : existing.getThroughMessageId();
        List<AssistantConversationMessageEntity> page = messages.listAfter(
                userId, conversationId, afterCreatedAt, afterMessageId, sourceBatchSize);
        List<AssistantConversationMessageEntity> deltaRows = page.stream()
                .filter(row -> compare(row.getCreatedAt(), row.getId(), anchor.getCreatedAt(), anchor.getId()) < 0)
                .toList();

        int totalOlder = messages.countBefore(userId, conversationId, anchor.getCreatedAt(), anchor.getId());
        String summaryText = existing == null ? null : existing.getSummary();
        int coveredCount = existing == null ? 0 : existing.getCoveredMessageCount();

        if (!deltaRows.isEmpty()) {
            List<AssistantMessage> delta = deltaRows.stream()
                    .map(row -> new AssistantMessage(AssistantMessage.Role.fromWire(row.getRole()), row.getText()))
                    .toList();
            int sourceTokens = delta.stream().mapToInt(message -> tokens.count(message.text())).sum();
            if (sourceTokens - summaryOutputTokens >= minimumSavingsTokens) {
                try {
                    String updated = summarizer.summarize(summaryText, delta, summaryOutputTokens);
                    AssistantConversationMessageEntity through = deltaRows.get(deltaRows.size() - 1);
                    int newCoveredCount = coveredCount + deltaRows.size();
                    int changed = existing == null
                            ? summaries.insertIfAbsent(userId, conversationId, updated,
                            through.getCreatedAt(), through.getId(), newCoveredCount)
                            : summaries.advanceIfVersion(userId, conversationId, existing.getVersion(), updated,
                            through.getCreatedAt(), through.getId(), newCoveredCount);
                    if (changed == 1) {
                        summaryText = updated;
                        coveredCount = newCoveredCount;
                    } else {
                        AssistantConversationSummaryEntity winner = summaries.find(userId, conversationId);
                        if (winner != null
                                && compare(winner.getThroughCreatedAt(), winner.getThroughMessageId(),
                                anchor.getCreatedAt(), anchor.getId()) < 0) {
                            summaryText = winner.getSummary();
                            coveredCount = winner.getCoveredMessageCount();
                        }
                    }
                } catch (AssistantConversationSummarizer.SummaryFailure failure) {
                    log.info("assistant context compaction skipped: reason=SUMMARY_FAILURE conversationId={}",
                            conversationId);
                }
            }
        }

        int dropped = Math.max(0, totalOlder - Math.min(coveredCount, totalOlder));
        PreparedContext prepared = new PreparedContext(summaryText, recent, dropped,
                Math.min(coveredCount, totalOlder));
        return prepared;
    }

    private PreparedContext fitBudget(PreparedContext prepared, AssistantContext context, String currentText) {
        if (promptBuilder == null || context == null) return prepared;
        List<AssistantMessage> recent = new ArrayList<>(prepared.recentHistory());
        String summary = prepared.summary();
        int trimmed = 0;
        while (renderedTokens(context, summary, recent, currentText) > maxInputTokens && !recent.isEmpty()) {
            recent.remove(0);
            trimmed++;
        }
        if (renderedTokens(context, summary, recent, currentText) > maxInputTokens && summary != null) {
            summary = null;
        }
        if (renderedTokens(context, summary, recent, currentText) > maxInputTokens) {
            throw new ContextBudgetExceeded();
        }
        int dropped = prepared.droppedMessages() + trimmed;
        int summarized = summary == null ? 0 : prepared.summarizedMessages();
        if (prepared.summary() != null && summary == null) {
            dropped += prepared.summarizedMessages();
        }
        return new PreparedContext(summary, recent, dropped, summarized);
    }

    private int renderedTokens(AssistantContext context, String summary,
                               List<AssistantMessage> recent, String currentText) {
        return countMessageTokens(promptBuilder.buildMessages(context, summary, recent, currentText));
    }

    public boolean withinBudget(List<java.util.Map<String, String>> renderedMessages) {
        return countMessageTokens(renderedMessages) <= maxInputTokens;
    }

    private int countMessageTokens(List<java.util.Map<String, String>> renderedMessages) {
        return renderedMessages.stream()
                .mapToInt(message -> tokens.count(message.getOrDefault("role", ""))
                        + tokens.count(message.getOrDefault("content", "")) + 4)
                .sum() + 3;
    }

    private static int compare(Instant leftTime, UUID leftId, Instant rightTime, UUID rightId) {
        int time = leftTime.compareTo(rightTime);
        return time != 0 ? time : leftId.compareTo(rightId);
    }

    public record PreparedContext(String summary, List<AssistantMessage> recentHistory,
                                  int droppedMessages, int summarizedMessages) {
        public PreparedContext {
            recentHistory = recentHistory == null ? List.of() : List.copyOf(recentHistory);
        }
    }

    public static final class ContextBudgetExceeded extends RuntimeException {
        public ContextBudgetExceeded() { super("assistant prompt exceeds configured token budget"); }
    }
}
