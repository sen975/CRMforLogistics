package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TemplatePermissionMapperSqlTest {
    @Test
    void candidateQueryIsApprovedBoundedAndDueOnly() throws Exception {
        Method method = TemplateMapper.class.getMethod(
                "findPermissionReconciliationCandidates", UUID.class, Instant.class, int.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .toUpperCase(Locale.ROOT);

        assertThat(sql)
                .contains("UPPER(STATUS) = 'APPROVED'")
                .contains("CHANNEL_ACCOUNT_ID = #{CHANNELACCOUNTID}::UUID")
                .contains("DESIRED_ALLOW_SEND <> ALLOW_SEND")
                .contains("PERMISSION_SYNC_NEXT_ATTEMPT_AT <= #{NOW}".toUpperCase(Locale.ROOT))
                .contains("UPDATED_AT <= #{NOW}::TIMESTAMPTZ - INTERVAL '10 MINUTES'")
                .contains("LIMIT #{LIMIT}".toUpperCase(Locale.ROOT));
    }

    @Test
    void pendingClaimUsesVersionAndDesiredMismatchGuards() throws Exception {
        Method method = TemplateMapper.class.getMethod(
                "markPermissionPending", UUID.class, long.class, Instant.class);
        String sql = String.join(" ", method.getAnnotation(Update.class).value())
                .toUpperCase(Locale.ROOT);

        assertThat(sql)
                .contains("VERSION = #{EXPECTEDVERSION}".toUpperCase(Locale.ROOT))
                .contains("DESIRED_ALLOW_SEND <> ALLOW_SEND")
                .contains("UPDATED_AT <= #{NOW}::TIMESTAMPTZ - INTERVAL '10 MINUTES'")
                .contains("VERSION = VERSION + 1");
    }

    @Test
    void permissionSuccessUsesTypedTimestampForFailureRetryDeadline() throws Exception {
        Method method = TemplateMapper.class.getMethod(
                "markPermissionSucceeded", UUID.class, boolean.class, boolean.class, Instant.class);
        String sql = String.join(" ", method.getAnnotation(Update.class).value())
                .toUpperCase(Locale.ROOT);

        assertThat(sql)
                .contains("#{NOW}::TIMESTAMPTZ + INTERVAL '1 MINUTE'");
    }
}
