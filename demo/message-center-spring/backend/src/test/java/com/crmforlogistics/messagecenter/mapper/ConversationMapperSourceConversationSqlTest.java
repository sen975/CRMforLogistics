package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMapperSourceConversationSqlTest {

    @Test
    void sourceConversationUpsertMatchesThePartialUniqueIndexPredicate() throws NoSuchMethodException {
        String sql = String.join(" ", ConversationMapper.class
                .getMethod("getOrCreateSourceConversation", UUID.class, UUID.class)
                .getAnnotation(Select.class)
                .value()).toLowerCase();

        assertThat(sql)
                .contains("on conflict (channel_account_id, source_conversation_id) "
                        + "where source_conversation_id is not null do update");
    }
}
