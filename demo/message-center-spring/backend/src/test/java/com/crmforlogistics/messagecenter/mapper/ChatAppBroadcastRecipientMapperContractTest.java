package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAppBroadcastRecipientMapperContractTest {

    @Test
    void pendingCandidateQueryCastsNullableProviderTimeAsTimestamptz() throws Exception {
        String sql = String.join(" ", ChatAppBroadcastRecipientMapper.class
                .getMethod("findPendingCandidates", UUID.class, String.class, String.class,
                        String.class, Instant.class, int.class)
                .getAnnotation(Select.class).value()).toLowerCase();

        assertThat(sql).contains(
                "and (cast(#{providersentat} as timestamptz) is null or b.submitted_at between "
                        + "cast(#{providersentat} as timestamptz) - interval '24 hours' and "
                        + "cast(#{providersentat} as timestamptz) + interval '5 minutes')");
    }
}
