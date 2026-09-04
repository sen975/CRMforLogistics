package com.crmforlogistics.messagecenter.mapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UserChannelOwnerSchemaContractTest {

    @Test
    void migrationDefinesOwnerColumnsAndAccountUniqueness() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V47__user_channel_address_book_owner.sql"));
        String compact = sql.replaceAll("\\s+", "").toLowerCase();

        assertThat(compact).contains("channel_accounts");
        assertThat(compact).contains("owner_user_id");
        assertThat(compact).contains("contacts");
        assertThat(compact).contains("call_records");
        assertThat(compact).contains("contact_id");
        assertThat(compact).contains("contact_tags");
        assertThat(compact).contains("channel_type");
        assertThat(compact).contains("chatapp");
        assertThat(compact).contains("email");
    }

    @Test
    void entitiesExposeExplicitOwnerFields() throws Exception {
        String channelAccount = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/entity/ChannelAccountEntity.java"));
        String contact = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/entity/ContactEntity.java"));
        String callRecord = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/entity/CallRecordEntity.java"));

        assertThat(channelAccount).contains("ownerUserId");
        assertThat(contact).contains("ownerUserId");
        assertThat(callRecord).contains("ownerUserId");
        assertThat(callRecord).contains("contactId");
    }

    @Test
    void mapperContractUsesOwnerParameters() throws Exception {
        String channelAccount = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/ChannelAccountMapper.java"));
        String contact = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMapper.java"));
        String callRecord = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/CallRecordMapper.java"));

        assertThat(channelAccount).contains("ownerId");
        assertThat(contact).contains("ownerId");
        assertThat(callRecord).contains("ownerId");
    }
}
