package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiTopicEntityPersistenceMappingTest {
    @Test
    void selectByIdMapsOnlyColumnsThatExistInAiTopics() {
        assertProjectionField("ownerLabel");
        assertProjectionField("contactDisplayName");
        assertProjectionField("contactRemark");
        assertProjectionField("contactChannelType");
        assertProjectionField("contactChannelNickname");
        assertProjectionField("reviewSourceTopicTitle");
    }

    private static void assertProjectionField(String fieldName) {
        try {
            TableField mapping = AiTopicEntity.class.getDeclaredField(fieldName).getAnnotation(TableField.class);
            assertThat(mapping).isNotNull();
            assertThat(mapping.exist()).isFalse();
        } catch (NoSuchFieldException error) {
            throw new AssertionError(error);
        }
    }
}
