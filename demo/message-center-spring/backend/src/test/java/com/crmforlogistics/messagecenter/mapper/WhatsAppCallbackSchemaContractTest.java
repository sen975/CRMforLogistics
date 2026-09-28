package com.crmforlogistics.messagecenter.mapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppCallbackSchemaContractTest {
    @Test
    void migrationDefinesPhoneAndAccountCallbackConfigAndAudit() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V79__whatsapp_cams_webhook_configuration.sql"));
        String normalized = sql.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("create table whatsapp_cams_callback_configs")
                .contains("create table whatsapp_cams_callback_audits")
                .contains("provider_scope_id uuid not null")
                .contains("channel_account_id uuid")
                .contains("desired_up_callback_url varchar(2048)")
                .contains("desired_status_callback_url varchar(2048)")
                .contains("level in ('phone', 'account')")
                .contains("http_flag in ('y', 'n')")
                .contains("queue_flag in ('y', 'n')")
                .contains("last_apply_status in ('never_applied', 'succeeded', 'failed')")
                .contains("references whatsapp_provider_scopes(id)")
                .contains("references channel_accounts(id)")
                .contains("unique index")
                .contains("where level = 'account'")
                .contains("where level = 'phone'");
    }

    @Test
    void applyClaimMigrationAddsExpiringClaimAndUnknownOutcomeContracts() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V89__whatsapp_cams_callback_apply_claim.sql"));
        String normalized = sql.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("add column if not exists apply_token uuid")
                .contains("add column if not exists apply_started_at timestamptz")
                .contains("'applying'")
                .contains("'submission_unknown'")
                .contains("ix_whatsapp_cams_callback_apply_claim");
    }

    /**
     * V89 在**执行之后**被补进过一段改 audit 约束的 ALTER，库中 checksum 与文件因此不符（漂移）。
     * V89 已按库中 checksum 精确复原，即**不含**那段 ALTER。
     * 这条断言把「复原后的形态」钉住：谁要是把那段 ALTER 塞回 V89，flyway validate 会重新报
     * checksum mismatch，而这里会先一步说清原因。该段 ALTER 的修复职责已移交 V97。
     */
    @Test
    void applyClaimMigrationAsExecutedDoesNotCarryTheAuditResultConstraint() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V89__whatsapp_cams_callback_apply_claim.sql"));
        String normalized = sql.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized).doesNotContain("ck_whatsapp_cams_callback_audit_result");
    }

    /**
     * 审计结果约束的第三个取值必须由**前向**迁移补上。
     * 代码在「结果未知」时会写 SUBMISSION_UNKNOWN，而 V79 建的 CHECK 只允许两值 ⇒ INSERT 撞约束、
     * 异常被 auditSafely 的 catch 吞掉 ⇒ 审计静默丢失。断言落在 V97 而不是 V89 上，是因为
     * 修复只有写进新迁移才会真正作用到已存在的库。
     */
    @Test
    void auditResultConstraintAcceptsUnknownOutcomeInForwardMigration() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V97__whatsapp_cams_callback_audit_result_submission_unknown.sql"));
        String normalized = sql.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("drop constraint if exists ck_whatsapp_cams_callback_audit_result")
                .contains("add constraint ck_whatsapp_cams_callback_audit_result")
                .contains("'submission_unknown'");
    }

    /**
     * V84 被删后留下的孤儿列必须由前向迁移删除。
     * 重建的 V84 只负责让账本完整与 validate 通过，不能顺手删列（会重新弄脏 checksum）。
     */
    @Test
    void orphanMessageSyncCursorColumnIsDroppedInForwardMigration() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V98__channel_accounts_drop_orphan_message_sync_cursor.sql"));
        String normalized = sql.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("alter table channel_accounts")
                .contains("drop column if exists message_last_synced_at");
    }

    @Test
    void mapperUsesClaimTokenAndPersistsUnknownOutcome() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppCallbackConfigMapper.java"));
        assertThat(mapper)
                .contains("apply_started_at < now() - interval '5 minutes'")
                .contains("updateApplyUnknown")
                .contains("last_apply_status = 'SUBMISSION_UNKNOWN'")
                .contains("and apply_token = #{token}::uuid");
    }

    @Test
    void sourceContractsExposeConfigAndAppendOnlyAuditMappers() throws Exception {
        String entity = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/entity/WhatsAppCallbackConfigEntity.java"));
        String configMapper = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppCallbackConfigMapper.java"));
        String auditMapper = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/WhatsAppCallbackAuditMapper.java"));

        assertThat(entity)
                .contains("private UUID providerScopeId;")
                .contains("private UUID channelAccountId;")
                .contains("private String desiredUpCallbackUrl;")
                .contains("private String desiredStatusCallbackUrl;")
                .contains("private long version;");
        assertThat(configMapper)
                .contains("findPhone")
                .contains("findAccount")
                .contains("updateApplySuccess")
                .contains("updateApplyFailure");
        assertThat(auditMapper).contains("insert");
    }
}
