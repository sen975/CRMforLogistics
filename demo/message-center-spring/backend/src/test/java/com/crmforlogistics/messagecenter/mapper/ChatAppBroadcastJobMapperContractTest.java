package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAppBroadcastJobMapperContractTest {

    @Test
    void expiredSubmitJobsAreNotClaimedForAutomaticRetry() throws Exception {
        String sql = String.join(" ", ChatAppBroadcastJobMapper.class
                .getMethod("claimDue", String.class, Instant.class, Instant.class, int.class)
                .getAnnotation(Select.class).value()).toLowerCase();

        assertThat(sql).contains("job_type = 'reconcile'");
        assertThat(sql).doesNotContain("status = 'processing' and lease_expires_at < #{now}");
    }
}
