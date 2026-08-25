package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.typehandler.JsonbStringTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.StringTypeHandler;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PGobject;

import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TemplateEntityJsonbMappingTest {

    private static final String TYPE_HANDLER_CLASS =
            "com.crmforlogistics.messagecenter.typehandler.JsonbStringTypeHandler";

    @Test
    void templateJsonFieldsUseTheJsonbTypeHandler() throws Exception {
        TableName table = TemplateEntity.class.getAnnotation(TableName.class);
        assertThat(table.autoResultMap()).isTrue();

        for (String fieldName : new String[]{"componentsJsonb", "examplesJsonb", "metadataJsonb"}) {
            TableField field = TemplateEntity.class.getDeclaredField(fieldName)
                    .getAnnotation(TableField.class);
            assertThat(field).as(fieldName).isNotNull();
            assertThat(field.jdbcType()).as(fieldName).isEqualTo(JdbcType.OTHER);
            assertThat(field.typeHandler().getName()).isEqualTo(TYPE_HANDLER_CLASS);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonbTypeHandlerBindsPostgresJsonbInsteadOfVarchar() throws Exception {
        TypeHandler<String> handler = (TypeHandler<String>) Class.forName(TYPE_HANDLER_CLASS)
                .getConstructor()
                .newInstance();
        PreparedStatement statement = mock(PreparedStatement.class);

        handler.setParameter(statement, 1, "{\"type\":\"BODY\"}", JdbcType.OTHER);

        var value = ArgumentCaptor.forClass(PGobject.class);
        verify(statement).setObject(eq(1), value.capture());
        assertThat(value.getValue().getType()).isEqualTo("jsonb");
        assertThat(value.getValue().getValue()).isEqualTo("{\"type\":\"BODY\"}");
    }

    @Test
    void packageScanningKeepsPlainStringsOutOfTheJsonbHandler() {
        TypeHandlerRegistry registry = new TypeHandlerRegistry();
        registry.register("com.crmforlogistics.messagecenter.typehandler");

        assertThat(registry.getTypeHandler(String.class))
                .isInstanceOf(StringTypeHandler.class);
        assertThat(registry.getTypeHandler(String.class, JdbcType.OTHER))
                .isInstanceOf(JsonbStringTypeHandler.class);
    }
}
