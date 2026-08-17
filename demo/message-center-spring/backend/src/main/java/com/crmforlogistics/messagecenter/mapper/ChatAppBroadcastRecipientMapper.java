package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastRecipientEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface ChatAppBroadcastRecipientMapper extends BaseMapper<ChatAppBroadcastRecipientEntity> {

    @Select("select * from chatapp_broadcast_recipients where broadcast_id = #{broadcastId}::uuid "
            + "order by created_at, id")
    List<ChatAppBroadcastRecipientEntity> findByBroadcastId(@Param("broadcastId") UUID broadcastId);

    @Select("select * from chatapp_broadcast_recipients where broadcast_id = #{broadcastId}::uuid "
            + "and status = 'FAILED_RECIPIENT' order by updated_at desc, id desc "
            + "limit #{limit} offset #{offset}")
    List<ChatAppBroadcastRecipientEntity> findFailures(
            @Param("broadcastId") UUID broadcastId,
            @Param("limit") int limit,
            @Param("offset") long offset);

    @Select("select count(*) from chatapp_broadcast_recipients "
            + "where broadcast_id = #{broadcastId}::uuid and status = 'FAILED_RECIPIENT'")
    long countFailures(@Param("broadcastId") UUID broadcastId);

    @Select("select * from chatapp_broadcast_recipients where broadcast_id = #{broadcastId}::uuid "
            + "and recipient_number_snapshot = #{number} limit 1")
    Optional<ChatAppBroadcastRecipientEntity> findByNumber(
            @Param("broadcastId") UUID broadcastId,
            @Param("number") String number);

    @Select("select * from chatapp_broadcast_recipients where id = #{id}::uuid for update")
    Optional<ChatAppBroadcastRecipientEntity> findByIdForUpdate(@Param("id") UUID id);

    @Update("update chatapp_broadcast_recipients set message_id = #{messageId}::uuid, "
            + "updated_at = #{updatedAt}, version = version + 1 "
            + "where id = #{id}::uuid and message_id is null")
    int linkMessageIfAbsent(@Param("id") UUID id,
                            @Param("messageId") UUID messageId,
                            @Param("updatedAt") Instant updatedAt);

    @Select("select * from chatapp_broadcast_recipients where broadcast_id = #{broadcastId}::uuid "
            + "and message_id is null order by created_at, id limit #{limit}")
    List<ChatAppBroadcastRecipientEntity> findWithoutMessage(
            @Param("broadcastId") UUID broadcastId, @Param("limit") int limit);

    @Update("update chatapp_broadcast_recipients set provider_message_id = #{providerMessageId}, "
            + "provider_unique_message_id = #{providerUniqueMessageId}, status = #{status}, "
            + "failure_reason = #{failureReason}, provider_sent_at = #{providerSentAt}, "
            + "last_reconciled_at = #{reconciledAt}, updated_at = #{reconciledAt}, "
            + "version = version + 1 where id = #{id}::uuid")
    int updateProviderStatus(@Param("id") UUID id,
                             @Param("providerMessageId") String providerMessageId,
                             @Param("providerUniqueMessageId") String providerUniqueMessageId,
                             @Param("status") String status,
                             @Param("failureReason") String failureReason,
                             @Param("providerSentAt") Instant providerSentAt,
                             @Param("reconciledAt") Instant reconciledAt);

    @Update("update chatapp_broadcast_recipients set status = #{status}, updated_at = #{updatedAt}, "
            + "version = version + 1 where broadcast_id = #{broadcastId}::uuid")
    int updateAllStatuses(@Param("broadcastId") UUID broadcastId,
                          @Param("status") String status,
                          @Param("updatedAt") Instant updatedAt);
}
