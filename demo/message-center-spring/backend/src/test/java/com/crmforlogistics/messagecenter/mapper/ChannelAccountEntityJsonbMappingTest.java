package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.typehandler.JsonbStringTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelAccountEntityJsonbMappingTest {

    @Test
    void encryptedConfigUsesTheJsonbTypeHandlerForReadAndWrite() throws Exception {
        TableName table = ChannelAccountEntity.class.getAnnotation(TableName.class);
        assertThat(table.autoResultMap()).isTrue();

        TableField field = ChannelAccountEntity.class.getDeclaredField("encryptedConfig")
                .getAnnotation(TableField.class);
        assertThat(field).isNotNull();
        assertThat(field.jdbcType()).isEqualTo(JdbcType.OTHER);
        assertThat(field.typeHandler()).isEqualTo(JsonbStringTypeHandler.class);
    }
}
