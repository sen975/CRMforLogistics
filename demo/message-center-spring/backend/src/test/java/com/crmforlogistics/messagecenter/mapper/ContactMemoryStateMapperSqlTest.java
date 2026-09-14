package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContactMemoryStateMapperSqlTest {

    @Test
    void claimReturnsAnIndependentFencingToken() throws Exception {
        var method = ContactMemoryStateMapper.class.getMethod(
                "claim", UUID.class, String.class, Instant.class);
        String sql = normalize(method.getAnnotation(Select.class).value());

        assertThat(sql).contains("lease_token = gen_random_uuid()")
                .contains("returning lease_token")
                .contains("lease_expires_at = #{leaseUntil}")
                .contains("status = 'PROCESSING'");
    }

    @Test
    void completionAndFailureRequireTheCurrentUnexpiredToken() throws Exception {
        var complete = ContactMemoryStateMapper.class.getMethod(
                "complete", UUID.class, UUID.class, String.class, UUID.class, Instant.class);
        var fail = ContactMemoryStateMapper.class.getMethod(
                "fail", UUID.class, UUID.class, String.class, String.class,
                int.class, Instant.class, boolean.class);

        String completeSql = normalize(complete.getAnnotation(Update.class).value());
        String failSql = normalize(fail.getAnnotation(Update.class).value());

        assertThat(completeSql).contains("lease_token = #{leaseToken}::uuid")
                .contains("status = 'PROCESSING'")
                .contains("lease_expires_at > now()")
                .contains("last_success_cursor = #{cursor}");
        assertThat(failSql).contains("lease_token = #{leaseToken}::uuid")
                .contains("status = 'PROCESSING'")
                .contains("lease_expires_at > now()");
    }

    private static String normalize(String[] fragments) {
        return String.join(" ", fragments).replace("&gt;", ">")
                .replace("&lt;", "<");
    }
}
