package com.crmforlogistics.messagecenter.mapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppAssignmentHistoryAccessSqlTest {

    @Test
    void contactAndMessageHistoryQueriesUseConversationGrantsInsteadOfCurrentAccountOwner() throws Exception {
        String contactMapper = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMapper.java"));
        String messageMapper = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/MessageMapper.java"));
        String contactIdentityMapper = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/mapper/ContactIdentityMapper.java"));
        String contactSql = contactMapper.toLowerCase().replaceAll("\\s+", " ");
        String messageSql = messageMapper.toLowerCase().replaceAll("\\s+", " ");
        String contactIdentitySql = contactIdentityMapper.toLowerCase().replaceAll("\\s+", " ");

        assertThat(contactSql).contains("conversation_access_grants");
        assertThat(messageSql).contains("conversation_access_grants");
        assertThat(messageSql).contains("cv.assigned_user_id");
        assertThat(contactIdentitySql).contains("conversation_access_grants");
        int historyStart = messageSql.indexOf("list messages for a conversation");
        int historyEnd = messageSql.indexOf("listmessagesbyconversations(");
        assertThat(historyStart).isPositive();
        assertThat(historyEnd).isGreaterThan(historyStart);
        String historySql = messageSql.substring(historyStart, historyEnd);
        assertThat(historySql).doesNotContain("ca.owner_user_id=#{ownerid}");

        int legacyContactHistoryStart = messageSql.indexOf("messageentity findbyidandowner");
        int legacyContactHistoryEnd = messageSql.indexOf("atomically insert a message");
        assertThat(legacyContactHistoryStart).isPositive();
        assertThat(legacyContactHistoryEnd).isGreaterThan(legacyContactHistoryStart);
        String legacyContactHistorySql = messageSql.substring(legacyContactHistoryStart, legacyContactHistoryEnd);
        assertThat(legacyContactHistorySql).doesNotContain("ca.owner_user_id=#{ownerid}");
        assertThat(legacyContactHistorySql).contains("conversation_access_grants");

        assertThat(messageSql).contains(
                "coalesce(#{channelaccountversion}, (select version from channel_accounts where id=#{channelaccountid}::uuid), 0)");
    }
}
