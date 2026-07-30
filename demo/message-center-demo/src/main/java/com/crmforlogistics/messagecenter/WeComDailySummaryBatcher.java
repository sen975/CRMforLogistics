package com.crmforlogistics.messagecenter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WeComDailySummaryBatcher {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_MESSAGES_PER_BATCH = 1_000;
    private static final int HARD_MAX_BATCHES = 32;

    private final Config config;
    private final WeComChatDataStore store;

    public WeComDailySummaryBatcher(Config config, WeComChatDataStore store) {
        this.config = config;
        this.store = store;
    }

    public List<ConversationDay> loadPreviousDay(Instant now) throws WeComChatDataException {
        if (now == null) throw new IllegalArgumentException("now is required");
        LocalDate day = now.atZone(BUSINESS_ZONE).toLocalDate().minusDays(1);
        return loadDay(day);
    }

    public List<ConversationDay> loadDay(LocalDate day) throws WeComChatDataException {
        if (day == null) throw new IllegalArgumentException("day is required");
        Instant from = day.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        List<WeComChatDataStore.StoredMessageReference> stored = store.load(from, to);
        Map<ConversationKey, List<MessageReference>> grouped = new LinkedHashMap<>();
        stored.stream()
                .sorted(Comparator.comparing(WeComChatDataStore.StoredMessageReference::userId)
                        .thenComparing(WeComChatDataStore.StoredMessageReference::externalUserId)
                        .thenComparingLong(WeComChatDataStore.StoredMessageReference::sendTime)
                        .thenComparing(WeComChatDataStore.StoredMessageReference::msgid))
                .forEach(message -> grouped.computeIfAbsent(
                                new ConversationKey(message.userId(), message.externalUserId()),
                                ignored -> new ArrayList<>())
                        .add(new MessageReference(message.msgid(), message.secretKey(),
                                message.sendTime(), message.msgType())));
        List<ConversationDay> result = new ArrayList<>(grouped.size());
        for (Map.Entry<ConversationKey, List<MessageReference>> entry : grouped.entrySet()) {
            result.add(new ConversationDay(day, entry.getKey().userId(),
                    entry.getKey().externalUserId(), List.copyOf(entry.getValue())));
        }
        return List.copyOf(result);
    }

    public List<SummaryBatch> split(ConversationDay day) {
        return split(day, config.wecomDailySummaryMaxBatches());
    }

    public List<SummaryBatch> split(ConversationDay day, int maxBatches) {
        validateDay(day);
        requireMaxBatches(maxBatches);
        int count = (day.messages().size() + MAX_MESSAGES_PER_BATCH - 1) / MAX_MESSAGES_PER_BATCH;
        if (count > maxBatches) throw new IllegalStateException("daily summary batch limit exceeded");
        List<Partition> partitions = new ArrayList<>(count);
        for (int from = 0; from < day.messages().size(); from += MAX_MESSAGES_PER_BATCH) {
            int to = Math.min(day.messages().size(), from + MAX_MESSAGES_PER_BATCH);
            partitions.add(new Partition(from, to, List.copyOf(day.messages().subList(from, to))));
        }
        return batches(day, partitions);
    }

    public List<SummaryBatch> bisect(List<SummaryBatch> current, int batchIndex, int maxBatches) {
        requireMaxBatches(maxBatches);
        if (current == null || current.isEmpty() || batchIndex < 0 || batchIndex >= current.size()) {
            throw new IllegalArgumentException("batch selection invalid");
        }
        SummaryBatch target = current.get(batchIndex);
        if (target.messages().size() < 2) {
            throw new IllegalStateException("single message batch cannot be split");
        }
        if (current.size() >= maxBatches) {
            throw new IllegalStateException("daily summary batch limit exceeded");
        }
        List<Partition> partitions = new ArrayList<>(current.size() + 1);
        for (int index = 0; index < current.size(); index++) {
            SummaryBatch batch = current.get(index);
            if (!batch.sameConversation(target)) {
                throw new IllegalArgumentException("mixed conversation batches");
            }
            if (index != batchIndex) {
                partitions.add(new Partition(batch.sliceStart(), batch.sliceEnd(), batch.messages()));
                continue;
            }
            int midpoint = batch.messages().size() / 2;
            int absoluteMidpoint = batch.sliceStart() + midpoint;
            partitions.add(new Partition(batch.sliceStart(), absoluteMidpoint,
                    List.copyOf(batch.messages().subList(0, midpoint))));
            partitions.add(new Partition(absoluteMidpoint, batch.sliceEnd(),
                    List.copyOf(batch.messages().subList(midpoint, batch.messages().size()))));
        }
        ConversationDay day = new ConversationDay(target.day(), target.userId(),
                target.externalUserId(), partitions.stream().flatMap(partition -> partition.messages().stream()).toList());
        return batches(day, partitions);
    }

    private static List<SummaryBatch> batches(ConversationDay day,
                                              List<Partition> partitions) {
        List<SummaryBatch> result = new ArrayList<>(partitions.size());
        for (int index = 0; index < partitions.size(); index++) {
            Partition partition = partitions.get(index);
            result.add(new SummaryBatch(day.day(), day.userId(), day.externalUserId(),
                    index, partitions.size(), partition.start(), partition.end(), partition.messages()));
        }
        return List.copyOf(result);
    }

    private static void validateDay(ConversationDay day) {
        if (day == null || day.day() == null || !bounded(day.userId(), 128)
                || !bounded(day.externalUserId(), 128) || day.messages() == null
                || day.messages().isEmpty()) {
            throw new IllegalArgumentException("conversation day invalid");
        }
        for (MessageReference message : day.messages()) {
            if (message == null || !bounded(message.msgid(), 256)
                    || !bounded(message.secretKey(), 512) || message.sendTime() < 0
                    || !bounded(message.msgType(), 32)) {
                throw new IllegalArgumentException("message reference invalid");
            }
        }
    }

    private static void requireMaxBatches(int maxBatches) {
        if (maxBatches < 1 || maxBatches > HARD_MAX_BATCHES) {
            throw new IllegalArgumentException("max batches invalid");
        }
    }

    private static boolean bounded(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum;
    }

    public record ConversationDay(LocalDate day, String userId, String externalUserId,
                                  List<MessageReference> messages) {}

    public record MessageReference(String msgid, String secretKey, long sendTime, String msgType) {}

    public record SummaryBatch(LocalDate day, String userId, String externalUserId,
                               int batchIndex, int batchCount, int sliceStart, int sliceEnd,
                               List<MessageReference> messages) {
        private boolean sameConversation(SummaryBatch other) {
            return day.equals(other.day) && userId.equals(other.userId)
                    && externalUserId.equals(other.externalUserId);
        }
    }

    private record ConversationKey(String userId, String externalUserId) {}
    private record Partition(int start, int end, List<MessageReference> messages) {}
}
