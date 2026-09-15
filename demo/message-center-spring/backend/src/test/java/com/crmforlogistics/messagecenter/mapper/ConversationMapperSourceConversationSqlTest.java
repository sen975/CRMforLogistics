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

    @Test
    void tagSearchModeExcludesGroupsAndDropsTheNameBranch() throws Exception {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("userId", UUID.randomUUID());
        params.put("search", "客户");
        params.put("tagSearch", true);
        params.put("cursorPinned", null);
        params.put("cursorRank", null);
        params.put("cursorAt", null);
        params.put("cursorKey", null);
        params.put("limit", 20);

        String tagSql = renderUnifiedList(params);
        assertThat(tagSql).contains("and 1 = 0");
        assertThat(tagSql).contains("from contact_taggings ct");
        assertThat(tagSql).contains("t.owner_user_id = ?::uuid");
        assertThat(tagSql).doesNotContain("coalesce(c.remark, '') ilike");

        params.put("tagSearch", false);
        String contactSql = renderUnifiedList(params);
        assertThat(contactSql).doesNotContain("and 1 = 0");
        assertThat(contactSql).contains("coalesce(c.remark, '') ilike");
        assertThat(contactSql).doesNotContain("from contact_taggings ct");
    }

    private static String renderUnifiedList(java.util.Map<String, Object> params) throws Exception {
        String script = String.join(" ", java.util.Arrays.stream(ConversationMapper.class.getMethods())
                .filter(method -> method.getName().equals("listUnified"))
                .findFirst().orElseThrow()
                .getAnnotation(Select.class)
                .value()).strip();
        var sqlSource = new XMLLanguageDriver()
                .createSqlSource(new Configuration(), script, java.util.Map.class);
        return sqlSource.getBoundSql(params).getSql().toLowerCase().replaceAll("\\s+", " ");
    }
}
