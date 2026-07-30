package com.crmforlogistics.messagecenter;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeComDailySummaryBatcherTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsPreviousBeijingDayAndGroupsEachDirectConversation() throws Exception {
        Config config = config(Map.of());
        long from = Instant.parse("2026-07-28T16:00:00Z").getEpochSecond();
        long to = Instant.parse("2026-07-29T16:00:00Z").getEpochSecond();
        write(config.wecomDataFile(), List.of(
                reference("before", "secret-before", "external-b", "employee-a", from - 1, "2"),
                reference("b-2", "secret-b-2", "external-b", "employee-a", from + 2, "2"),
                reference("c-1", "secret-c-1", "external-c", "employee-a", from + 1, "2"),
                reference("b-1", "secret-b-1", "external-b", "employee-a", from + 1, "2"),
                reference("at-end", "secret-end", "external-b", "employee-a", to, "2")
        ));

        WeComDailySummaryBatcher batcher = new WeComDailySummaryBatcher(
                config, new WeComChatDataStore(config));
        List<WeComDailySummaryBatcher.ConversationDay> days = batcher.loadPreviousDay(
                Instant.parse("2026-07-29T16:05:00Z"));

        assertEquals(2, days.size());
        assertEquals(LocalDate.of(2026, 7, 29), days.get(0).day());
        assertEquals("employee-a", days.get(0).userId());
        assertEquals("external-b", days.get(0).externalUserId());
        assertEquals(List.of("b-1", "b-2"),
                days.get(0).messages().stream().map(WeComDailySummaryBatcher.MessageReference::msgid).toList());
        assertEquals("external-c", days.get(1).externalUserId());
        assertEquals(List.of("c-1"),
                days.get(1).messages().stream().map(WeComDailySummaryBatcher.MessageReference::msgid).toList());
    }

    @Test
    void splitsAtOneThousandAndCanBisectAnOversizedOfficialBatch() {
        WeComDailySummaryBatcher batcher = new WeComDailySummaryBatcher(
                config(Map.of()), new WeComChatDataStore(config(Map.of())));
        List<WeComDailySummaryBatcher.MessageReference> messages = messages(1_001);
        WeComDailySummaryBatcher.ConversationDay day = new WeComDailySummaryBatcher.ConversationDay(
                LocalDate.of(2026, 7, 29), "employee-a", "external-b", messages);

        List<WeComDailySummaryBatcher.SummaryBatch> initial = batcher.split(day, 32);
        List<WeComDailySummaryBatcher.SummaryBatch> bisected = batcher.bisect(initial, 0, 32);

        assertEquals(List.of(1000, 1), initial.stream().map(batch -> batch.messages().size()).toList());
        assertEquals(List.of(500, 500, 1),
                bisected.stream().map(batch -> batch.messages().size()).toList());
        assertEquals(List.of(0, 500, 1000),
                bisected.stream().map(WeComDailySummaryBatcher.SummaryBatch::sliceStart).toList());
        assertEquals(List.of(500, 1000, 1001),
                bisected.stream().map(WeComDailySummaryBatcher.SummaryBatch::sliceEnd).toList());
        assertEquals(List.of(0, 1, 2),
                bisected.stream().map(WeComDailySummaryBatcher.SummaryBatch::batchIndex).toList());
        assertEquals(List.of(3, 3, 3),
                bisected.stream().map(WeComDailySummaryBatcher.SummaryBatch::batchCount).toList());
    }

    @Test
    void refusesMoreThanThirtyTwoBatchesAndCannotBisectOneMessage() {
        WeComDailySummaryBatcher batcher = new WeComDailySummaryBatcher(
                config(Map.of()), new WeComChatDataStore(config(Map.of())));
        WeComDailySummaryBatcher.ConversationDay tooLarge = new WeComDailySummaryBatcher.ConversationDay(
                LocalDate.of(2026, 7, 29), "employee-a", "external-b", messages(32_001));
        WeComDailySummaryBatcher.ConversationDay one = new WeComDailySummaryBatcher.ConversationDay(
                LocalDate.of(2026, 7, 29), "employee-a", "external-b", messages(1));

        assertThrows(IllegalStateException.class, () -> batcher.split(tooLarge, 32));
        List<WeComDailySummaryBatcher.SummaryBatch> single = batcher.split(one, 32);
        assertThrows(IllegalStateException.class, () -> batcher.bisect(single, 0, 32));
    }

    private Config config(Map<String, String> extra) {
        java.util.HashMap<String, String> values = new java.util.HashMap<>(extra);
        values.put("DATA_DIR", tempDir.toString());
        values.put("WECOM_DATA_FILE", tempDir.resolve("wecom-messages.jsonl").toString());
        return new Config(values);
    }

    private static List<WeComDailySummaryBatcher.MessageReference> messages(int count) {
        List<WeComDailySummaryBatcher.MessageReference> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(new WeComDailySummaryBatcher.MessageReference(
                    "msg-" + index, "secret-" + index, index, "2"));
        }
        return List.copyOf(result);
    }

    private static String reference(String msgid, String secretKey, String externalUserId,
                                    String userId, long sendTime, String msgType) {
        JsonObject object = new JsonObject();
        object.addProperty("msgid", msgid);
        object.addProperty("secret_key", secretKey);
        object.addProperty("external_userid", externalUserId);
        object.addProperty("userid", userId);
        object.addProperty("send_time", sendTime);
        object.addProperty("msgtype", msgType);
        object.addProperty("text", "must-not-be-projected");
        return object.toString();
    }

    private static void write(Path path, List<String> lines) throws Exception {
        Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }
}
