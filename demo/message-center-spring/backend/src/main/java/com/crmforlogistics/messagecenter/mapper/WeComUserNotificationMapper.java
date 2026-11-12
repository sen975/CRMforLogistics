package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.WeComUserNotificationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface WeComUserNotificationMapper extends BaseMapper<WeComUserNotificationEntity> {

    @Insert("insert into wecom_user_notifications (id, conversation_id, channel_account_id, "
            + "recipient_user_id, recipient_wecom_user_id, auth_corp_id, agent_id, channel_type, "
            + "contact_label, message_count, last_preview, first_message_at, send_after, status) "
            + "values (#{id}::uuid, #{conversationId}::uuid, #{channelAccountId}::uuid, "
            + "#{recipientUserId}::uuid, #{recipientWecomUserId}, #{authCorpId}, #{agentId}, "
            + "#{channelType}, #{contactLabel}, #{messageCount}, #{lastPreview}, #{firstMessageAt}, "
            + "#{sendAfter}, 'PENDING') "
            + "on conflict (conversation_id, recipient_user_id) where status = 'PENDING' "
            + "do update set message_count = wecom_user_notifications.message_count + 1, "
            + "last_preview = excluded.last_preview, updated_at = now()")
    int upsertPending(WeComUserNotificationEntity entity);

    @Select("select * from wecom_user_notifications "
            + "where status = 'PENDING' and send_after <= #{now} "
            + "order by send_after, created_at limit #{batchSize}")
    List<WeComUserNotificationEntity> listDue(@Param("now") Instant now,
                                              @Param("batchSize") int batchSize);

    @Update("update wecom_user_notifications set status = 'SENDING', version = version + 1, "
            + "updated_at = now() where id = #{id}::uuid and status = 'PENDING'")
    int claim(@Param("id") UUID id);

    @Update("update wecom_user_notifications set status = 'SENT', sent_at = #{sentAt}, "
            + "attempt_count = attempt_count + 1, last_error = null, version = version + 1, "
            + "updated_at = now() where id = #{id}::uuid and status = 'SENDING'")
    int markSent(@Param("id") UUID id, @Param("sentAt") Instant sentAt);

    @Update("update wecom_user_notifications set status = 'PENDING', send_after = #{sendAfter}, "
            + "attempt_count = attempt_count + 1, last_error = #{lastError}, version = version + 1, "
            + "updated_at = now() where id = #{id}::uuid and status = 'SENDING'")
    int retryLater(@Param("id") UUID id, @Param("sendAfter") Instant sendAfter,
                   @Param("lastError") String lastError);

    @Update("update wecom_user_notifications set status = 'FAILED', "
            + "attempt_count = attempt_count + 1, last_error = #{lastError}, version = version + 1, "
            + "updated_at = now() where id = #{id}::uuid and status = 'SENDING'")
    int markFailed(@Param("id") UUID id, @Param("lastError") String lastError);

    @Select("with stuck as ("
            + "select id from wecom_user_notifications "
            + "where status = 'SENDING' and updated_at < #{staleBefore} "
            + "for update skip locked"
            + ") update wecom_user_notifications n set status = 'PENDING', updated_at = now(), "
            + "version = version + 1 from stuck where n.id = stuck.id returning n.id")
    List<UUID> recoverStuck(@Param("staleBefore") Instant staleBefore);
}
