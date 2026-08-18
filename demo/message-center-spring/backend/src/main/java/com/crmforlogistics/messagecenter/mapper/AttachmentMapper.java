package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.AttachmentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface AttachmentMapper extends BaseMapper<AttachmentEntity> {
    @Select("select exists(select 1 from attachments where message_id = #{messageId}::uuid " +
            "and object_key = #{objectKey} and storage_status = 'ready' and deleted_at is null)")
    boolean existsReadyForMessage(@Param("messageId") UUID messageId,
                                  @Param("objectKey") String objectKey);

    @Select("select * from attachments where message_id = #{messageId}::uuid " +
            "and storage_status = 'ready' and deleted_at is null order by created_at asc")
    List<AttachmentEntity> listReadyByMessageId(@Param("messageId") UUID messageId);

    @Select({
            "<script>",
            "select * from attachments where message_id in",
            "<foreach collection='messageIds' item='messageId' open='(' separator=',' close=')'>",
            "#{messageId}::uuid",
            "</foreach>",
            "and storage_status = 'ready' and deleted_at is null",
            "order by message_id asc, created_at asc",
            "</script>"
    })
    List<AttachmentEntity> listReadyByMessageIds(@Param("messageIds") List<UUID> messageIds);

    @Select("select a.* from attachments a " +
            "join messages m on m.id = a.message_id " +
            "join conversations cv on cv.id = m.conversation_id " +
            "where a.id = #{attachmentId}::uuid and a.storage_status = 'ready' " +
            "and a.deleted_at is null and (cv.assigned_user_id = #{actorUserId}::uuid " +
            "or exists (select 1 from team_members tm where tm.team_id = cv.assigned_team_id " +
            "and tm.user_id = #{actorUserId}::uuid) " +
            "or exists (select 1 from conversation_access_grants g where g.conversation_id = cv.id " +
            "and g.user_id = #{actorUserId}::uuid and g.revoked_at is null " +
            "and (g.expires_at is null or g.expires_at > now())) " +
            "or exists (select 1 from user_roles ur join roles r on r.id = ur.role_id " +
            "where ur.user_id = #{actorUserId}::uuid and r.code = 'admin')) limit 1")
    AttachmentEntity findReadableById(@Param("attachmentId") UUID attachmentId,
                                      @Param("actorUserId") UUID actorUserId);
}
