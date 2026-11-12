package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.mapper.AiTopicGenerationJobMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicItemMapper;
import com.crmforlogistics.messagecenter.mapper.AiTopicMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.mapper.WeComMessageSummaryJobMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiTopicMergeReconciliationContractTest {

    @Test
    void automaticGenerationIsIdempotentForOwnerAndInputFingerprint() throws Exception {
        Method method = AiTopicGenerationJobMapper.class.getMethod("insertAutomaticIfAbsent",
                String.class, UUID.class, UUID.class, UUID.class, String.class, String.class, Instant.class);
        String sql = sql(method.getAnnotation(Insert.class).value());

        assertThat(sql)
                .contains("owner_type", "owner_id", "input_fingerprint")
                .contains("on conflict (owner_type, owner_id, input_fingerprint) do nothing");
    }

    @Test
    void legacyArchivedMessageCandidatesBelongToContactsMergedIntoTarget() throws Exception {
        Method method = MessageMapper.class.getMethod("listArchivedMergedContactMessages", UUID.class, int.class);
        String sql = sql(method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("t.owner_type='contact'", "t.status='archived'")
                .contains("source_contact.status='merged'")
                .contains("source_contact.merged_to_id=#{targetcontactid}::uuid")
                .contains("ca.channel_type in ('chatapp','email')");
    }

    @Test
    void legacyPersonalWeComReconciliationExcludesGroupSummaries() throws Exception {
        Method method = WeComMessageSummaryJobMapper.class.getMethod(
                "listArchivedMergedContactSummaries", UUID.class, int.class);
        String sql = sql(method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("t.owner_type='contact'", "t.status='archived'")
                .contains("sc.conversation_type='direct'")
                .doesNotContain("sc.conversation_type='group'");
    }

    @Test
    void legacyMigratedSourceCanOnlyMoveFromArchivedMergedContactTopic() throws Exception {
        Method method = AiTopicItemMapper.class.getMethod("moveArchivedMergedSourceToTopic",
                UUID.class, UUID.class, String.class, UUID.class);
        String sql = sql(method.getAnnotation(Update.class).value());

        assertThat(sql)
                .contains("old_topic.owner_type='contact'", "old_topic.status='archived'")
                .contains("old_contact.status='merged'")
                .contains("old_contact.merged_to_id=#{targetcontactid}::uuid")
                .contains("<otherwise>1=0</otherwise>");
    }

    @Test
    void contactTimelineDoesNotExposeArchivedSourceTopics() throws Exception {
        Method method = AiTopicMapper.class.getMethod("listReady", UUID.class);
        String sql = sql(method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("t.contact_id=#{contactid}::uuid")
                .contains("t.status='ready'")
                .doesNotContain("t.status='archived'");
    }

    @Test
    void futureContactMergeTransfersReadyTopicsInsteadOfArchivingThem() throws Exception {
        Method method = AiTopicMapper.class.getMethod("transferReadyByContact", UUID.class, UUID.class);
        String sql = sql(method.getAnnotation(Update.class).value());

        assertThat(sql)
                .contains("owner_id=#{targetcontactid}::uuid", "contact_id=#{targetcontactid}::uuid")
                .contains("owner_type='contact'", "owner_id=#{sourcecontactid}::uuid", "status='ready'")
                .doesNotContain("status='archived'");
    }

    private static String sql(String[] fragments) {
        return String.join(" ", fragments).replaceAll("\\s+", " ").toLowerCase();
    }
}
