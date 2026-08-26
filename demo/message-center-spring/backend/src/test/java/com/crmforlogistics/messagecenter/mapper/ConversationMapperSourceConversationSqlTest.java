package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
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

    @Test
    void unifiedListKeepsGroupsIndependentAndFiltersByBoundEmployeeParticipation() throws Exception {
        String sql = String.join(" ", ConversationMapper.class
                .getMethod("listUnified", UUID.class, String.class, String.class, String.class, int.class)
                .getAnnotation(Select.class)
                .value()).toLowerCase();

        assertThat(sql)
                .contains("union all")
                .contains("'wecom_group:' || sc.id::text")
                .contains("sc.conversation_type = 'group'")
                .contains("binding.user_id = #{userid}::uuid")
                .contains("viewer_party.provider_party_id = binding.wecom_user_id")
                .contains("viewer_participant.participant_status = 'observed'")
                .contains("direct_sc.conversation_type = 'direct'")
                .contains("direct_sc.contact_identity_id = ci.id")
                .contains("sort_at &lt; #{cursorat}::timestamptz")
                .contains("sort_key &lt; #{cursorkey}")
                .contains("order by sort_at desc nulls last, sort_key desc")
                .contains("group by sc.id");
    }

    @Test
    void unifiedListAnnotationIsValidMyBatisXml() throws Exception {
        String script = String.join(" ", ConversationMapper.class
                .getMethod("listUnified", UUID.class, String.class, String.class, String.class, int.class)
                .getAnnotation(Select.class)
                .value());

        new XMLLanguageDriver().createSqlSource(new Configuration(), script, java.util.Map.class);
    }
}
