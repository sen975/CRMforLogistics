package com.crmforlogistics.messagecenter.mapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantConversationSummarySchemaTest {

    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V100__assistant_conversation_summaries.sql");

    @Test
    void migrationDefinesOwnerScopedSummaryProjectionAndCursor() throws Exception {
        String sql = Files.readString(MIGRATION).toLowerCase();

        assertThat(sql).contains("create table assistant_conversation_summaries")
                .contains("user_id uuid not null")
                .contains("conversation_id uuid not null")
                .contains("summary varchar(4000) not null")
                .contains("through_message_id uuid not null")
                .contains("through_created_at timestamptz not null")
                .contains("covered_message_count integer not null")
                .contains("version bigint not null")
                .contains("unique (user_id, conversation_id)")
                .contains("check (version > 0)")
                .contains("unique (id, user_id, conversation_id)")
                .contains("foreign key (through_message_id, user_id, conversation_id)")
                .contains("references assistant_conversation_messages (id, user_id, conversation_id)");
    }
}
