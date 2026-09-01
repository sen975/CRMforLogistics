package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.mapper.WeComChatDataMessageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComChatDataRetention {
    private static final int BATCH_SIZE = 200;
    private static final int MAX_CANDIDATE_SCAN = 8_000;
    private static final int MAX_BATCHES_PER_RUN = 64;

    private final AppConfig config;
    private final WeComChatDataMessageMapper mapper;
    private final WeComViewerReferenceLeaseRegistry leases;
    private final WeComMessageSummaryRepository summaries;

    public WeComChatDataRetention(AppConfig config,
                                  WeComChatDataMessageMapper mapper,
                                  WeComViewerReferenceLeaseRegistry leases) {
        this(config, mapper, leases, null);
    }

    @Autowired
    public WeComChatDataRetention(AppConfig config,
                                  WeComChatDataMessageMapper mapper,
                                  WeComViewerReferenceLeaseRegistry leases,
                                  WeComMessageSummaryRepository summaries) {
        this.config = config;
        this.mapper = mapper;
        this.leases = leases;
        this.summaries = summaries;
    }

    public RetentionResult enforce() {
        int maxMessages = config.wecomChatDataStoreMaxMessages();
        long maxBytes = config.wecomChatDataStoreMaxBytes();
        if (maxMessages < 1 || maxBytes < 1) {
            throw new IllegalStateException("WeCom chatdata retention budgets must be positive");
        }
        long deleted = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            WeComChatDataMessageMapper.RetentionUsage usage = mapper.retentionUsage();
            if (withinBudget(usage, maxMessages, maxBytes)) {
                return new RetentionResult(deleted, true);
            }
            Set<String> leased = leases.leasedMessageIds();
            int candidateLimit = Math.min(MAX_CANDIDATE_SCAN, BATCH_SIZE + leased.size());
            List<WeComChatDataMessageMapper.RetentionCandidate> candidates =
                    mapper.oldestRetentionCandidates(candidateLimit);
            Set<String> protectedBySummary = summaries == null ? Set.of()
                    : summaries.countNonTerminalByMsgids(candidates.stream()
                            .map(WeComChatDataMessageMapper.RetentionCandidate::msgid)
                            .filter(java.util.Objects::nonNull).toList());
            List<UUID> deleteIds = selectDeletes(usage, candidates, leased, protectedBySummary,
                    maxMessages, maxBytes);
            if (deleteIds.isEmpty()) {
                return new RetentionResult(deleted, false);
            }
            int removed = mapper.deleteRetentionCandidates(deleteIds);
            if (removed < 1) {
                return new RetentionResult(deleted, false);
            }
            deleted += removed;
        }
        WeComChatDataMessageMapper.RetentionUsage usage = mapper.retentionUsage();
        return new RetentionResult(deleted, withinBudget(usage, maxMessages, maxBytes));
    }

    private static List<UUID> selectDeletes(
            WeComChatDataMessageMapper.RetentionUsage usage,
            List<WeComChatDataMessageMapper.RetentionCandidate> candidates,
            Set<String> leased,
            Set<String> protectedBySummary,
            int maxMessages,
            long maxBytes) {
        if (usage == null || candidates == null || candidates.isEmpty()) return List.of();
        long remainingMessages = usage.messageCount();
        long remainingBytes = usage.storedBytes();
        List<UUID> ids = new ArrayList<>();
        for (WeComChatDataMessageMapper.RetentionCandidate candidate : candidates) {
            if (candidate == null || candidate.id() == null || candidate.msgid() == null
                    || leased.contains(candidate.msgid())
                    || protectedBySummary.contains(candidate.msgid())) {
                continue;
            }
            ids.add(candidate.id());
            remainingMessages--;
            remainingBytes = Math.max(0, remainingBytes - Math.max(0, candidate.storedBytes()));
            if (remainingMessages <= maxMessages && remainingBytes <= maxBytes) break;
        }
        return List.copyOf(ids);
    }

    private static boolean withinBudget(WeComChatDataMessageMapper.RetentionUsage usage,
                                        int maxMessages, long maxBytes) {
        return usage != null && usage.messageCount() <= maxMessages && usage.storedBytes() <= maxBytes;
    }

    public record RetentionResult(long deleted, boolean withinBudget) {}
}
