package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.mapper.ContactMemoryTriggerEventMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContactMemoryTriggerEventMapperSqlTest {
    @Test
    void enqueueIsIdempotentAndUsesPersistedMessageBoundary() throws Exception {
        var method = ContactMemoryTriggerEventMapper.class.getMethod(
                "enqueue", UUID.class, UUID.class, Long.class, Instant.class, Instant.class);
        String sql = normalize(method.getAnnotation(Insert.class).value());

        assertThat(sql).contains("from messages m")
                .contains("m.ingest_sequence")
                .contains("m.received_at")
                .contains("c.created_by")
                .contains("on conflict (message_id) do nothing");
    }

    @Test
    void claimAndCompletionAreLeaseGuarded() throws Exception {
        var claim = ContactMemoryTriggerEventMapper.class.getMethod(
                "claimDue", Instant.class, String.class, Instant.class, int.class);
        var applied = ContactMemoryTriggerEventMapper.class.getMethod(
                "markApplied", UUID.class, UUID.class);
        var failed = ContactMemoryTriggerEventMapper.class.getMethod(
                "markFailed", UUID.class, UUID.class, String.class, String.class,
                int.class, Instant.class, boolean.class);

        assertThat(normalize(claim.getAnnotation(Select.class).value()))
                .contains("for update skip locked")
                .contains("lease_token = gen_random_uuid()")
                .contains("limit #{limit}");
        assertThat(normalize(applied.getAnnotation(Update.class).value()))
                .contains("lease_token = #{leaseToken}::uuid")
                .contains("lease_expires_at > now()");
        assertThat(normalize(failed.getAnnotation(Update.class).value()))
                .contains("status = case when #{terminal} then 'FAILED' else 'PENDING' end")
                .contains("lease_token = #{leaseToken}::uuid");
    }

    private static String normalize(String[] fragments) {
        return String.join(" ", fragments).replace("&gt;", ">")
                .replace("&lt;", "<");
    }
}
