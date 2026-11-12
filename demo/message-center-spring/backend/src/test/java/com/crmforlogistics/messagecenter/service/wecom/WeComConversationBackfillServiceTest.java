package com.crmforlogistics.messagecenter.service.wecom;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeComConversationBackfillServiceTest {
    private static final Instant FROM = Instant.parse("2026-08-20T00:00:00Z");
    private final WeComConversationBackfillService service = new WeComConversationBackfillService(
            Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void usesIndependentCursorAndAggregatesDirectGroupAndDuplicates() {
        AtomicInteger calls = new AtomicInteger();
        var report = service.run(new WeComConversationBackfillService.BackfillRequest(
                        FROM, FROM.plus(Duration.ofDays(1)), "backfill-start", 10,
                        Duration.ofMinutes(1), false, true), cursor -> {
                    calls.incrementAndGet();
                    return new WeComConversationBackfillService.Page(false, "backfill-end", List.of(
                            new WeComConversationBackfillService.Message("m1", FROM.plusSeconds(1), WeComConversationBackfillService.Kind.DIRECT),
                            new WeComConversationBackfillService.Message("m2", FROM.plusSeconds(2), WeComConversationBackfillService.Kind.GROUP),
                            new WeComConversationBackfillService.Message("m1", FROM.plusSeconds(3), WeComConversationBackfillService.Kind.DUPLICATE)));
                }, WeComConversationBackfillService.Message::kind);

        assertThat(calls).hasValue(1);
        assertThat(report).isEqualTo(new WeComConversationBackfillService.BackfillReport(
                1, "backfill-end", false, 2, 1, 1, 1, 0));
    }

    @Test
    void requiresConfirmationAndKeepsFiveDayWindow() {
        var request = new WeComConversationBackfillService.BackfillRequest(
                FROM, FROM.plus(Duration.ofDays(6)), "", 1, Duration.ofMinutes(1), false, false);
        assertThatThrownBy(() -> service.run(request, cursor -> new WeComConversationBackfillService.Page(false, "", List.of()), message -> WeComConversationBackfillService.Kind.DIRECT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
