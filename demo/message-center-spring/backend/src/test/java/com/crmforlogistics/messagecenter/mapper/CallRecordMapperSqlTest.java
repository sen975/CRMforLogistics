package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class CallRecordMapperSqlTest {
    @Test
    void replacePersistsTheCurrentTranscriptRevisionId() throws Exception {
        Method replace = CallRecordMapper.class.getMethod(
                "replace", CallRecordEntity.class, long.class);

        String sql = replace.getAnnotation(Update.class).value()[0];

        assertThat(sql).contains("current_revision_id = #{entity.currentRevisionId}::uuid");
    }

    @Test
    void contactAccessQueriesUseCreatedByInsteadOfLegacyContactOwner() throws Exception {
        for (String methodName : new String[]{"findByIdAndOwner", "listByOwnerAndContact",
                "listByOwnerAndAnchors"}) {
            Method method = java.util.Arrays.stream(CallRecordMapper.class.getMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst().orElseThrow();
            Select select = method.getAnnotation(Select.class);
            assertThat(select).isNotNull();
            String sql = String.join(" ", select.value());
            assertThat(sql).contains("created_by");
            assertThat(sql).doesNotContain("cr.owner_user_id = #{ownerId}");
        }
    }

    @Test
    void anchorTimelineQueryQualifiesJoinedTableOrderingColumns() throws Exception {
        Method method = CallRecordMapper.class.getMethod(
                "listByOwnerAndAnchors", java.util.UUID.class, java.util.Set.class);
        Select select = method.getAnnotation(Select.class);
        String sql = String.join(" ", select.value());

        assertThat(sql).contains("ORDER BY cr.occurred_at, cr.id");
    }

    @Test
    void contactBindingDoesNotRewriteLegacyOwnerColumn() throws Exception {
        Method method = CallRecordMapper.class.getMethod("updateContactBinding",
                java.util.UUID.class, java.util.UUID.class, java.util.UUID.class,
                String.class, long.class);
        String sql = method.getAnnotation(Update.class).value()[0];
        assertThat(sql).doesNotContain("SET owner_user_id");
        assertThat(sql).contains("contact_id = #{contactId}::uuid");
    }

    @Test
    void recoveryOnlyResetsProcessingRowsWithExpiredLeases() throws Exception {
        Method method = CallRecordMapper.class.getMethod("recoverProcessing", java.time.Instant.class);
        String sql = String.join(" ", method.getAnnotation(Update.class).value());
        assertThat(sql).contains("transcription_lease_expires_at IS NOT NULL");
        assertThat(sql).contains("transcription_lease_expires_at < #{now}");
    }

    @Test
    void phoneRepositoryQueryUsesOwnerScopedKeysetAndLimit() throws Exception {
        Method method = CallRecordMapper.class.getMethod("searchPhoneRepositoryByOwner",
                java.util.UUID.class, String.class, java.time.Instant.class,
                java.util.UUID.class, int.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());
        assertThat(sql).contains("(cr.occurred_at, cr.id)");
        assertThat(sql).contains("LIMIT #{limit}");
    }
}
