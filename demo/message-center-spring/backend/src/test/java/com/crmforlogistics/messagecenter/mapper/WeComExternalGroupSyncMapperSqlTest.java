package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class WeComExternalGroupSyncMapperSqlTest {
    @Test
    void finalProjectionOnlyClassifiesPreviouslyUnknownUnseenGroupsAsInternal() throws Exception {
        String sql = updateSql("markUnknownUnseenInternal");

        assertThat(sql).contains(
                "sc.group_kind='unknown'",
                "sc.first_seen_at <= sync.created_at",
                "not exists",
                "sc.provider_conversation_key='group:' || item.chat_id");
    }

    @Test
    void seenIdsAreProjectedAsExternalWithoutUsingDisplayNameAsTypeEvidence() throws Exception {
        String sql = updateSql("markSeenExternal");

        assertThat(sql).contains("group_kind='external'", "item.sync_id", "provider_conversation_key")
                .doesNotContain("display_name is not null");
    }

    private static String updateSql(String methodName) throws Exception {
        Method method = Arrays.stream(WeComExternalGroupSyncMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        return String.join(" ", method.getAnnotation(Update.class).value())
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
