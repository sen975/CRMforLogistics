package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MessageControlMapperContractTest {
    @Test
    void templateOperationClaimNeverPicksRetiredOperations() throws Exception {
        String sql = String.join(" ", TemplateOperationMapper.class
                .getMethod("claimUnknown", String.class, Instant.class, Instant.class, int.class)
                .getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("operation_status = 'SUBMISSION_UNKNOWN'")
                .contains("operation_type <> 'RETIRED'");
    }

    @Test
    void outboxDoesNotReclaimUnknownProviderSubmissions() throws Exception {
        String outboxSql = String.join(" ", OutboxJobMapper.class
                .getMethod("claimDue", String.class, Instant.class, int.class)
                .getAnnotation(Select.class).value());
        String inboxSql = String.join(" ", ChannelEventMapper.class
                .getMethod("claimDue", String.class, Instant.class, int.class)
                .getAnnotation(Select.class).value());

        assertThat(outboxSql)
                .contains("status in ('pending', 'retry_wait')")
                .contains("attempt_count < max_attempts")
                .doesNotContain("status in ('pending', 'retry_wait', 'processing')");
        assertThat(OutboxJobMapper.class.getMethod("completeIfOwned", java.util.UUID.class,
                String.class, java.time.Instant.class, java.time.Instant.class))
                .isNotNull();
        assertThat(inboxSql)
                .contains("processing_status in ('received', 'retry_wait', 'processing')");
    }

    @Test
    void everyOutboxOutcomeRequiresCurrentLeaseOwner() throws Exception {
        String completedSql = String.join(" ", OutboxJobMapper.class
                .getMethod("completeIfOwned", java.util.UUID.class, String.class,
                        Instant.class, Instant.class)
                .getAnnotation(Update.class).value());
        String deadSql = String.join(" ", OutboxJobMapper.class
                .getMethod("markDeadIfOwned", java.util.UUID.class, String.class,
                        String.class, String.class, Instant.class)
                .getAnnotation(Update.class).value());
        String retrySql = String.join(" ", OutboxJobMapper.class
                .getMethod("retryIfOwned", java.util.UUID.class, String.class,
                        Instant.class, String.class, String.class, Instant.class)
                .getAnnotation(Update.class).value());

        assertThat(completedSql).contains("status = 'processing'").contains("lease_owner");
        assertThat(deadSql).contains("status = 'processing'").contains("lease_owner");
        assertThat(retrySql).contains("status = 'processing'").contains("lease_owner");
    }

    @Test
    void expiredOutboxRecoveryMapsReturnedMessageIdsAsQuery() throws Exception {
        var method = OutboxJobMapper.class.getMethod(
                "recoverExpiredProcessing", Instant.class);

        assertThat(method.getAnnotation(Select.class)).isNotNull();
        assertThat(method.getAnnotation(Update.class)).isNull();
    }

    @Test
    void statusEventInsertPersistsProviderAndFailureAuditFields() throws Exception {
        String sql = String.join(" ", MessageStatusEventMapper.class
                .getMethod("insertIgnore",
                        com.crmforlogistics.messagecenter.entity.MessageStatusEventEntity.class)
                .getAnnotation(Insert.class).value());

        assertThat(sql)
                .contains("provider_event_id")
                .contains("reason_code")
                .contains("reason_message");
    }

    @Test
    void messageDeliveryUpdatesDoNotRewriteJsonMetadata() throws Exception {
        String statusSql = String.join(" ", MessageMapper.class
                .getMethod("updateDeliveryStatus", java.util.UUID.class, String.class,
                        String.class, Instant.class)
                .getAnnotation(Update.class).value());
        String providerSql = String.join(" ", MessageMapper.class
                .getMethod("updateProviderMessageId", java.util.UUID.class, String.class)
                .getAnnotation(Update.class).value());

        assertThat(statusSql)
                .contains("provider_message_id")
                .contains("current_status")
                .contains("current_status_at")
                .doesNotContain("metadata_jsonb");
        assertThat(providerSql)
                .contains("provider_message_id")
                .doesNotContain("metadata_jsonb");

        String unknownSql = String.join(" ", MessageMapper.class
                .getMethod("markSubmissionUnknownIfUnresolved", java.util.UUID.class,
                        Instant.class)
                .getAnnotation(Update.class).value());
        assertThat(unknownSql)
                .contains("current_status in ('pending', 'processing')")
                .doesNotContain("metadata_jsonb");
    }

    @Test
    void conversationSendAccessUsesExistingAssignmentGrantAndAdminModel() throws Exception {
        String sql = String.join(" ", ConversationMapper.class
                .getMethod("findAccessibleForMessage", java.util.UUID.class,
                        java.util.UUID.class, java.util.UUID.class, boolean.class)
                .getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("assigned_user_id")
                .contains("team_members")
                .contains("conversation_access_grants")
                .contains("user_roles")
                .contains("r.code = 'admin'")
                .contains("for update");
    }
}
