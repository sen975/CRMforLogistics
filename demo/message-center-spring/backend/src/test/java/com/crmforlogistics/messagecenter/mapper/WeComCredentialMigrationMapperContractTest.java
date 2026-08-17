package com.crmforlogistics.messagecenter.mapper;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeComCredentialMigrationMapperContractTest {

    @Test
    void omitsUuidCursorPredicateWhenCursorIsNull() throws Exception {
        assertNullCursorSql("nextInstallations");
        assertNullCursorSql("nextChatDataMessages");
    }

    @Test
    void includesUuidCursorPredicateWhenCursorIsPresent() throws Exception {
        assertPresentCursorSql("nextInstallations");
        assertPresentCursorSql("nextChatDataMessages");
    }

    private static void assertNullCursorSql(String methodName) throws Exception {
        BoundSql boundSql = boundSql(methodName, null);

        assertThat(boundSql.getSql())
                .doesNotContain("? IS NULL")
                .doesNotContain("id > ?");
    }

    private static void assertPresentCursorSql(String methodName) throws Exception {
        BoundSql boundSql = boundSql(methodName, UUID.randomUUID());

        assertThat(boundSql.getSql()).contains("id > ?");
    }

    private static BoundSql boundSql(String methodName, UUID afterId) throws Exception {
        Method method = WeComCredentialMigrationMapper.class
                .getMethod(methodName, UUID.class, int.class);
        String script = String.join(" ", method.getAnnotation(Select.class).value());
        var sqlSource = new XMLLanguageDriver()
                .createSqlSource(new Configuration(), script, Map.class);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("afterId", afterId);
        parameters.put("limit", 200);
        return sqlSource.getBoundSql(parameters);
    }
}
