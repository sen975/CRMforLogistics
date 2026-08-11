package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class CallRecordMapperSqlTest {
    @Test
    void replacePersistsTheCurrentTranscriptRevisionId() throws Exception {
        Method replace = CallRecordMapper.class.getMethod(
                "replace", CallRecordEntity.class, long.class);

        String sql = replace.getAnnotation(Update.class).value()[0];

        assertThat(sql).contains("current_revision_id = #{entity.currentRevisionId}::uuid");
    }
}
