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
        String sql = String.join(" ", java.util.Arrays.stream(ConversationMapper.class.getMethods())
                .filter(method -> method.getName().equals("listUnified"))
                .findFirst().orElseThrow()
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
                .contains("newer_direct_sc.conversation_type = 'direct'")
                .contains("newer_direct_sc.contact_identity_id = newer_ci.id")
                .contains("newer_m.received_at &gt; pref.hidden_at")
                .contains("newer_cv.assigned_user_id = #{userid}::uuid")
                .contains("conversation_id = newer_cv.id")
                .contains("<if test=\"search == null or search == ''\">")
                .doesNotContain("#{search} is not null")
                .contains("not exists")
                .contains("grouped_sc.conversation_type = 'group'")
                .contains("sc.group_kind='internal'")
                .contains("sc.group_kind='external'")
                .contains("'内部群聊'")
                .contains("'外部群聊'")
                .doesNotContain("coalesce(nullif(sc.display_name, ''), '企业微信群')")
                .contains("#{cursorpinned}::boolean")
                .contains("sort_rank &gt; #{cursorrank}")
                .contains("sort_at &lt; #{cursorat}::timestamptz")
                .contains("sort_key &lt; #{cursorkey}")
                .contains("order by pinned desc, sort_rank nulls last, sort_at desc nulls last, sort_key desc")
                .contains("group by sc.id");
    }

    @Test
    void unifiedListAnnotationIsValidMyBatisXml() throws Exception {
        String script = String.join(" ", java.util.Arrays.stream(ConversationMapper.class.getMethods())
                .filter(method -> method.getName().equals("listUnified"))
                .findFirst().orElseThrow()
                .getAnnotation(Select.class)
                .value());

        new XMLLanguageDriver().createSqlSource(new Configuration(), script, java.util.Map.class);
    }

    @Test
    void contactWeComSourceLookupReturnsDirectConversationsOnly() throws Exception {
        String sql = String.join(" ", ConversationMapper.class
                .getMethod("listAccessibleWeComSourceForContact", UUID.class, UUID.class)
                .getAnnotation(Select.class)
                .value()).toLowerCase();

        assertThat(sql)
                .contains("sc.conversation_type = 'direct'")
                .doesNotContain("sc.conversation_type = 'group'");
    }

    @Test
    void relatedGroupLookupRequiresBothContactAndCurrentViewerParticipation() throws Exception {
        String sql = String.join(" ", ConversationMapper.class
                .getMethod("listAccessibleWeComGroupsForContact", UUID.class, UUID.class)
                .getAnnotation(Select.class)
                .value()).toLowerCase();

        assertThat(sql)
                .doesNotContain("select distinct sc.id")
                .contains("sc.conversation_type = 'group'")
                .contains("ci.contact_id = #{contactid}::uuid")
                .contains("ci.identity_scope = cv.channel_account_id::text")
                .contains("contact_party.provider_party_id = ci.identity_value")
                .contains("viewer_party.provider_party_id = binding.wecom_user_id")
                .contains("sc.group_kind='internal'")
                .contains("sc.group_kind='external'")
                .contains("'内部群聊'")
                .contains("'外部群聊'")
                .doesNotContain("coalesce(nullif(sc.display_name, ''), '企业微信群')");
    }
}
