package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.entity.WeComChatDataIngestFailureEntity;
import com.crmforlogistics.messagecenter.entity.WeComPartyEntity;
import com.crmforlogistics.messagecenter.entity.WeComSourceConversationEntity;
import com.crmforlogistics.messagecenter.entity.WeComSourceParticipantEntity;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WeComConversationSchemaContractTest {
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V23__wecom_conversation_identity.sql");

    @Test
    void migrationDefinesAuditableConversationIdentityContract() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("ALTER TABLE wecom_installations ADD COLUMN corp_name")
                .contains("CREATE TABLE wecom_parties")
                .contains("UNIQUE (installation_id, party_type, provider_party_id)")
                .contains("CREATE TABLE wecom_source_conversations")
                .contains("UNIQUE (installation_id, provider_conversation_key)")
                .contains("conversation_type IN ('DIRECT', 'GROUP')")
                .contains("conversation_type <> 'GROUP' OR contact_identity_id IS NULL")
                .contains("ADD COLUMN source_conversation_id uuid")
                .contains("ux_conversation_source_identity")
                .contains("CREATE TABLE wecom_source_conversation_participants")
                .contains("CREATE TABLE wecom_chatdata_ingest_failures")
                .contains("ALTER COLUMN external_userid DROP NOT NULL")
                .contains("ux_wecom_chatdata_installation_msgid");
        assertThat(sql).doesNotContain("DROP TABLE").doesNotContain("DROP COLUMN");
    }

    @Test
    void entitiesExposeOnlyNonBodyFailureAndSourceIdentityFields() {
        assertThat(WeComPartyEntity.class.getAnnotation(com.baomidou.mybatisplus.annotation.TableName.class).value())
                .isEqualTo("wecom_parties");
        assertThat(WeComSourceConversationEntity.class.getAnnotation(com.baomidou.mybatisplus.annotation.TableName.class).value())
                .isEqualTo("wecom_source_conversations");
        assertThat(WeComSourceParticipantEntity.class.getAnnotation(com.baomidou.mybatisplus.annotation.TableName.class).value())
                .isEqualTo("wecom_source_conversation_participants");
        assertThat(WeComChatDataIngestFailureEntity.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("body", "bodyText", "secretKey", "plaintext");
    }
}
