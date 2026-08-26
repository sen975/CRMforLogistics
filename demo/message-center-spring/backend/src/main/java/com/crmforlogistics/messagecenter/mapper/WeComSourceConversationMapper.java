package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.WeComSourceConversationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.util.UUID;

@Mapper
public interface WeComSourceConversationMapper extends BaseMapper<WeComSourceConversationEntity> {
    @Select("insert into wecom_source_conversations (id, installation_id, provider_conversation_key, "
            + "conversation_type, first_seen_at, last_seen_at, created_at, updated_at) values "
            + "(gen_random_uuid(), #{installationId}::uuid, #{providerConversationKey}, #{conversationType}, "
            + "now(), now(), now(), now()) on conflict (installation_id, provider_conversation_key) "
            + "do update set last_seen_at = now(), updated_at = now() returning id")
    UUID upsertObserved(@Param("installationId") UUID installationId,
                        @Param("providerConversationKey") String providerConversationKey,
                        @Param("conversationType") String conversationType);

    @Select("select * from wecom_source_conversations where installation_id = #{installationId}::uuid "
            + "and conversation_type = 'GROUP' order by last_seen_at desc, id limit #{limit}")
    java.util.List<WeComSourceConversationEntity> listGroupBackfillCandidates(
            @Param("installationId") UUID installationId, @Param("limit") int limit);

    @Update("update wecom_source_conversations set display_name = #{displayName}, updated_at = now() "
            + "where id = #{sourceConversationId}::uuid")
    int updateDisplayName(@Param("sourceConversationId") UUID sourceConversationId,
                          @Param("displayName") String displayName);
}
