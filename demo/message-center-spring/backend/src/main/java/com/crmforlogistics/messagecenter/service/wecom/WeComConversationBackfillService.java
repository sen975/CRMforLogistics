package com.crmforlogistics.messagecenter.service.wecom;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Bounded, independently-cursored ChatData backfill orchestration. */
public final class WeComConversationBackfillService {
    public static final Duration RETENTION_WINDOW = Duration.ofDays(5);
    private static final int MAX_PAGES = 100;
    private static final Duration MAX_RUNTIME = Duration.ofMinutes(10);
    private final Clock clock;

    public WeComConversationBackfillService() {
        this(Clock.systemUTC());
    }

    public WeComConversationBackfillService(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public BackfillReport run(BackfillRequest request, PageFetcher fetcher, PagePublisher publisher) {
        validate(request);
        Objects.requireNonNull(fetcher, "fetcher");
        Objects.requireNonNull(publisher, "publisher");
        Instant deadline = clock.instant().plus(request.maxRuntime());
        String cursor = request.initialCursor() == null ? "" : request.initialCursor();
        Counts counts = new Counts();
        int pages = 0;
        while (pages < request.maxPages() && clock.instant().isBefore(deadline)) {
            Page page = fetcher.fetch(cursor);
            pages++;
            for (Message message : page.messages()) {
                if (message.sendTime().isBefore(request.fromInclusive())
                        || !message.sendTime().isBefore(request.toExclusive())) continue;
                if (request.dryRun()) counts = counts.add(message.kind());
                else counts = counts.add(publisher.publish(message));
            }
            cursor = page.nextCursor();
            if (!page.hasMore()) return counts.report(pages, cursor, false);
        }
        return counts.report(pages, cursor, true);
    }

    private static void validate(BackfillRequest request) {
        if (request == null || request.fromInclusive() == null || request.toExclusive() == null
                || !request.fromInclusive().isBefore(request.toExclusive())
                || Duration.between(request.fromInclusive(), request.toExclusive()).compareTo(RETENTION_WINDOW) > 0
                || request.maxPages() < 1 || request.maxPages() > MAX_PAGES
                || request.maxRuntime() == null || request.maxRuntime().isZero()
                || request.maxRuntime().isNegative() || request.maxRuntime().compareTo(MAX_RUNTIME) > 0
                || (!request.dryRun() && !request.confirmed())) {
            throw new IllegalArgumentException("backfill request is invalid or not confirmed");
        }
    }

    @FunctionalInterface public interface PageFetcher { Page fetch(String cursor); }
    @FunctionalInterface public interface PagePublisher { Kind publish(Message message); }

    public record BackfillRequest(Instant fromInclusive, Instant toExclusive, String initialCursor,
                                  int maxPages, Duration maxRuntime, boolean dryRun, boolean confirmed) {}

    public record Page(boolean hasMore, String nextCursor, List<Message> messages) {
        public Page {
            if (nextCursor == null) nextCursor = "";
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    public record Message(String msgid, Instant sendTime, Kind kind) {
        public Message {
            if (msgid == null || msgid.isBlank() || sendTime == null || kind == null) {
                throw new IllegalArgumentException("backfill message is invalid");
            }
        }
    }

    public enum Kind { DIRECT, GROUP, DUPLICATE, FAILED }

    public record BackfillReport(int pages, String finalCursor, boolean incomplete,
                                 int stored, int direct, int group, int duplicate, int failed) {}

    private record Counts(int stored, int direct, int group, int duplicate, int failed) {
        Counts() { this(0, 0, 0, 0, 0); }
        Counts add(Kind kind) {
            return switch (kind) {
                case DIRECT -> new Counts(stored + 1, direct + 1, group, duplicate, failed);
                case GROUP -> new Counts(stored + 1, direct, group + 1, duplicate, failed);
                case DUPLICATE -> new Counts(stored, direct, group, duplicate + 1, failed);
                case FAILED -> new Counts(stored, direct, group, duplicate, failed + 1);
            };
        }
        BackfillReport report(int pages, String cursor, boolean incomplete) {
            return new BackfillReport(pages, cursor, incomplete, stored, direct, group, duplicate, failed);
        }
    }
}
