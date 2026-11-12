package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class AiTopicMapperGroupOwnerLabelSqlTest {
    @Test
    void groupTopicQueriesExposeOnlySafeOwnerLabels() throws Exception {
        assertSafeGroupLabel("listReadyReferencedGroupTopics");
        assertSafeGroupLabel("listReadyGroupTopics");
    }

    @Test
    void annotationSqlDoesNotPassXmlEntitiesToPostgres() {
        for (Method method : AiTopicMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            if (select == null) continue;
            String sql = String.join(" ", select.value());
            if (sql.startsWith("<script>")) continue;
            assertThat(sql)
                    .doesNotContain("&lt;", "&gt;", "&amp;");
        }
    }

    private static void assertSafeGroupLabel(String methodName) throws Exception {
        Method method = AiTopicMapper.class.getMethod(methodName, java.util.UUID.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value()).toLowerCase();

        assertThat(sql)
                .doesNotContain("provider_conversation_key")
                .contains("sc.group_kind='internal'", "sc.group_kind='external'",
                        "'外部群聊'", "'内部群聊'")
                .doesNotContain("party_type='external_contact'");
    }
}
