package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.AiTopicInboxRequestEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface AiTopicInboxRequestMapper extends BaseMapper<AiTopicInboxRequestEntity> {
    String SAFE_GROUP_OWNER_LABEL = "case when sc.group_kind='INTERNAL' then '内部群聊' "
            + "when sc.group_kind='EXTERNAL' and nullif(sc.display_name, '') is not null "
            + "and sc.display_name not like 'group:%' then sc.display_name else '外部群聊' end";

    @Insert("insert into ai_topic_inbox_requests (id, topic_id, requested_by_user_id, status, created_at) "
            + "values (gen_random_uuid(), #{topicId}::uuid, #{userId}::uuid, 'PENDING', now()) on conflict do nothing")
    int insertPendingIfAbsent(@Param("topicId") UUID topicId, @Param("userId") UUID userId);

    @Select("select * from ai_topic_inbox_requests where topic_id=#{topicId}::uuid and status='PENDING' limit 1")
    AiTopicInboxRequestEntity findPendingByTopicId(@Param("topicId") UUID topicId);

    @Select("select r.*, t.title as topic_title, t.owner_type, t.owner_id, "
            + SAFE_GROUP_OWNER_LABEL + " as owner_label "
            + "from ai_topic_inbox_requests r join ai_topics t on t.id=r.topic_id "
            + "join wecom_source_conversations sc on sc.id=t.owner_id "
            + "where r.status='PENDING' and t.status='READY' and t.owner_type='WECOM_GROUP' "
            + "order by r.created_at asc, r.id")
    java.util.List<AiTopicInboxRequestEntity> listPendingGroupStoreRequests();

    @Update("update ai_topic_inbox_requests set status='APPROVED', reviewed_by_user_id=#{adminId}::uuid, "
            + "reviewed_at=now(), reason=null where id=#{requestId}::uuid and status='PENDING'")
    int approvePending(@Param("requestId") UUID requestId, @Param("adminId") UUID adminId);

    @Update("update ai_topic_inbox_requests set status='REJECTED', reviewed_by_user_id=#{adminId}::uuid, "
            + "reviewed_at=now(), reason=left(#{reason}, 1000) where id=#{requestId}::uuid and status='PENDING'")
    int rejectPending(@Param("requestId") UUID requestId, @Param("adminId") UUID adminId,
                      @Param("reason") String reason);
}
