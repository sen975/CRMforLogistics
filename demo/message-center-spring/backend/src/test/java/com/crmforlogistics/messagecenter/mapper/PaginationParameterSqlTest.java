package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PaginationParameterSqlTest {
    @Test
    void paginationLimitsUseBoundParameters() {
        List<Class<?>> mapperTypes = List.of(MessageMapper.class, ContactMapper.class, ConversationMapper.class);

        for (Class<?> mapperType : mapperTypes) {
            for (Method method : mapperType.getMethods()) {
                Select select = method.getAnnotation(Select.class);
                if (select == null) continue;
                String sql = String.join(" ", Arrays.asList(select.value()));
                if (sql.contains("page.size")) {
                    assertThat(sql)
                            .as(mapperType.getSimpleName() + "." + method.getName())
                            .doesNotContain("${page.size}");
                    assertThat(sql).contains("#{page.size}");
                }
            }
        }
    }

    @Test
    void pageSizePlaceholderIsCompiledAsBoundParameter() {
        Configuration configuration = new Configuration();
        XMLLanguageDriver languageDriver = new XMLLanguageDriver();
        SqlSource sqlSource = languageDriver.createSqlSource(configuration, "select 1 limit #{page.size}", Map.class);
        BoundSql boundSql = sqlSource.getBoundSql(Map.of("page", Map.of("size", 100)));

        assertThat(boundSql.getSql()).contains("limit ?");
        assertThat(boundSql.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("page.size");
    }
}
