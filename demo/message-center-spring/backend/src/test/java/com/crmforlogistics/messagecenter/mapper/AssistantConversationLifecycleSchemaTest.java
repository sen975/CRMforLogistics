package com.crmforlogistics.messagecenter.mapper;

import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Select;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantConversationLifecycleSchemaTest {
    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V102__assistant_conversation_lifecycle.sql");
    private static final Path MAPPER =
            Path.of("src/main/java/com/crmforlogistics/messagecenter/mapper/AssistantConversationMapper.java");

    @Test
    void lifecycleIdentityAndTranscriptOwnershipAreScopedByUser() throws Exception {
        String sql = Files.readString(MIGRATION).toLowerCase();
        String mapper = Files.readString(MAPPER).toLowerCase();

        assertThat(sql).contains("primary key (user_id, id)")
                .contains("group by user_id, conversation_id")
                .contains("foreign key (user_id, conversation_id)")
                .contains("references assistant_conversations (user_id, id)")
                .contains("create unique index uq_assistant_conversations_one_active")
                .contains("where status = 'active'");
        assertThat(mapper).contains("on conflict (user_id, id) do nothing");

        String visibleQuery = AssistantConversationMapper.class
                .getMethod("listVisible", UUID.class)
                .getAnnotation(Select.class).value()[0].toLowerCase();
        assertThat(visibleQuery).contains("user_id = #{userid}::uuid")
                .contains("status in ('active', 'archived')")
                .contains("exists (select 1 from assistant_conversation_messages")
                .doesNotContain("'expired'", "'deleted'");
    }
}
