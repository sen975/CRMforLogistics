package com.crmforlogistics.messagecenter.mapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SyncCursorMapperSqlTest {
    @Test
    void storesTimestampAndMessageIdForAnAccountScopedCursor() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/SyncCursorMapper.java"));
        assertThat(sql)
                .contains("cursor_type = #{cursorType}")
                .contains("scope_key = #{scopeKey}")
                .contains("cursor_value = excluded.cursor_value")
                .contains("cursor_timestamp = excluded.cursor_timestamp");

        String sync = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/channel/chatapp/ChatAppMessageSyncService.java"));
        assertThat(sync)
                .contains("thenComparing(row -> firstNonBlank(row.getMessageId(), row.getUniqueMessageId()))")
                .contains("String cursorValue = timestamp.toEpochMilli() + \"|\" + messageId")
                .contains("syncCursorMapper.upsertCursor(accountId, \"CHATAPP_MESSAGES\", \"default\", cursorValue, timestamp)");
    }
}
