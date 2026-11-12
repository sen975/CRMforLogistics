package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailSubmissionMapperContractTest {
    @Test
    void submissionInsertCreatesLeaseAndStateTransitionsAreTokenFenced() throws Exception {
        Insert insert = EmailSubmissionMapper.class.getMethod("insert",
                com.crmforlogistics.messagecenter.entity.EmailSubmissionEntity.class)
                .getAnnotation(Insert.class);
        String insertSql = String.join(" ", insert.value());
        Update update = EmailSubmissionMapper.class.getMethod("update",
                com.crmforlogistics.messagecenter.entity.EmailSubmissionEntity.class)
                .getAnnotation(Update.class);
        String updateSql = String.join(" ", update.value());

        assertThat(insertSql).contains("lease_token", "lease_expires_at");
        assertThat(updateSql).contains("lease_token=#{leaseToken}")
                .contains("lease_expires_at > now()");
    }

    @Test
    void heartbeatRenewsOnlyTheActiveSubmissionLeaseWithMatchingToken() throws Exception {
        Update update = EmailSubmissionMapper.class.getMethod("renewLease",
                java.util.UUID.class, java.util.UUID.class).getAnnotation(Update.class);
        String sql = String.join(" ", update.value());

        assertThat(sql).contains("lease_token=#{leaseToken}::uuid")
                .contains("status in ('PENDING','SMTP_SENT')")
                .contains("lease_expires_at > now()")
                .contains("lease_expires_at=now() + interval '5 minutes'");
    }

    @Test
    void statusChangesAreCompareAndSetTransitions() throws Exception {
        Update update = EmailSubmissionMapper.class.getMethod("update",
                com.crmforlogistics.messagecenter.entity.EmailSubmissionEntity.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", update.value());

        assertThat(sql).contains("#{status}='SMTP_SENT' and status='PENDING'")
                .contains("#{status}='SENT' and status='SMTP_SENT'")
                .contains("#{status}='FAILED' and status='PENDING'")
                .contains("#{status}='UNKNOWN' and status in ('PENDING','SMTP_SENT')");
    }

    @Test
    void unknownSubmissionVisibilityIsOwnerScopedAndBounded() throws Exception {
        Select select = EmailSubmissionMapper.class.getMethod("listUnknownByOwner",
                java.util.UUID.class, int.class).getAnnotation(Select.class);
        String sql = String.join(" ", select.value());

        assertThat(sql).contains("s.owner_user_id=#{ownerId}::uuid")
                .contains("s.status='UNKNOWN'")
                .contains("limit #{limit}");
        assertThat(sql).doesNotContain("join channel_accounts");
    }

    @Test
    void interruptedPreFinalizationSubmissionsBecomeUnknownAfterGracePeriod() throws Exception {
        Update update = EmailSubmissionMapper.class.getMethod("markStaleSubmissionsUnknown",
                java.util.UUID.class, int.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", update.value());

        assertThat(sql).contains("status in ('PENDING','SMTP_SENT')")
                .contains("lease_expires_at <= now()")
                .contains("status='UNKNOWN'")
                .contains("lease_token")
                .contains("owner_user_id=#{ownerId}::uuid")
                .contains("order by lease_expires_at, id limit #{limit}")
                .contains("for update skip locked");
    }
}
