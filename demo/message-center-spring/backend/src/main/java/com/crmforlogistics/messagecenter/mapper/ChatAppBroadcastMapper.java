package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface ChatAppBroadcastMapper extends BaseMapper<ChatAppBroadcastEntity> {

    @Insert("insert into chatapp_broadcasts (id, channel_account_id, name, template_code, "
            + "template_name, template_body_snapshot, language_code, recipient_count, success_count, failed_count, "
            + "processing_count, status, client_request_id, request_fingerprint, retries_broadcast_id, "
            + "created_by_user_id, channel_account_version, created_at, updated_at, version) values (#{broadcast.id}::uuid, "
            + "#{broadcast.channelAccountId}::uuid, #{broadcast.name}, #{broadcast.templateCode}, "
            + "#{broadcast.templateName}, #{broadcast.templateBodySnapshot}, #{broadcast.languageCode}, #{broadcast.recipientCount}, "
            + "#{broadcast.successCount}, #{broadcast.failedCount}, #{broadcast.processingCount}, "
            + "#{broadcast.status}, #{broadcast.clientRequestId}, #{broadcast.requestFingerprint}, "
            + "#{broadcast.retriesBroadcastId}::uuid, #{broadcast.createdByUserId}::uuid, "
            + "#{broadcast.channelAccountVersion}, "
            + "#{broadcast.createdAt}, #{broadcast.updatedAt}, #{broadcast.version}) "
            + "on conflict (channel_account_id, client_request_id) do nothing")
    int insertIfAbsent(@Param("broadcast") ChatAppBroadcastEntity broadcast);

    @Select("select * from chatapp_broadcasts where channel_account_id = #{channelAccountId}::uuid "
            + "and client_request_id = #{clientRequestId} limit 1")
    Optional<ChatAppBroadcastEntity> findByIdempotency(
            @Param("channelAccountId") UUID channelAccountId,
            @Param("clientRequestId") String clientRequestId);

    @Select("select exists (select 1 from user_roles ur join roles r on r.id = ur.role_id "
            + "where ur.user_id = #{userId}::uuid and r.code = 'admin')")
    boolean isAdmin(@Param("userId") UUID userId);

    @Select("select exists (select 1 from chatapp_broadcasts where channel_account_id = "
            + "#{channelAccountId}::uuid and created_by_user_id = #{userId}::uuid)")
    boolean existsCreatedByAccount(@Param("channelAccountId") UUID channelAccountId,
                                   @Param("userId") UUID userId);

    @Select("select * from chatapp_broadcasts where id = #{id}::uuid for update")
    Optional<ChatAppBroadcastEntity> findByIdForUpdate(@Param("id") UUID id);

    @Update("update chatapp_broadcasts set status = #{status}, success_count = #{successCount}, "
            + "failed_count = #{failedCount}, processing_count = #{processingCount}, "
            + "reconciled_at = #{reconciledAt}, error_code = #{errorCode}, "
            + "error_message = #{errorMessage}, updated_at = #{updatedAt}, version = version + 1 "
            + "where id = #{id}::uuid")
    int updateAggregate(@Param("id") UUID id,
                        @Param("status") String status,
                        @Param("successCount") int successCount,
                        @Param("failedCount") int failedCount,
                        @Param("processingCount") int processingCount,
                        @Param("reconciledAt") Instant reconciledAt,
                        @Param("errorCode") String errorCode,
                        @Param("errorMessage") String errorMessage,
                        @Param("updatedAt") Instant updatedAt);

    @Update("update chatapp_broadcasts set status = 'SUBMITTED', "
            + "provider_group_message_id = #{groupMessageId}, provider_request_id = #{requestId}, "
            + "provider_code = #{providerCode}, submitted_at = #{submittedAt}, "
            + "error_code = null, error_message = null, updated_at = #{submittedAt}, version = version + 1 "
            + "where id = #{id}::uuid")
    int markSubmitted(@Param("id") UUID id,
                      @Param("groupMessageId") String groupMessageId,
                      @Param("requestId") String requestId,
                      @Param("providerCode") String providerCode,
                      @Param("submittedAt") Instant submittedAt);

    @Update("update chatapp_broadcasts set status = #{status}, error_code = #{errorCode}, "
            + "error_message = #{errorMessage}, updated_at = #{updatedAt}, version = version + 1 "
            + "where id = #{id}::uuid")
    int markError(@Param("id") UUID id,
                  @Param("status") String status,
                  @Param("errorCode") String errorCode,
                  @Param("errorMessage") String errorMessage,
                  @Param("updatedAt") Instant updatedAt);

    @Update("update chatapp_broadcasts set status = #{status}, updated_at = #{updatedAt}, "
            + "version = version + 1 where id = #{id}::uuid")
    int updateStatus(@Param("id") UUID id,
                     @Param("status") String status,
                     @Param("updatedAt") Instant updatedAt);

    @Update("update chatapp_broadcasts set last_reconciliation_request_id = left(#{requestId}, 255), "
            + "last_reconciliation_provider_code = left(#{providerCode}, 100), "
            + "error_code = left(#{errorCode}, 100), "
            + "error_message = left(#{errorMessage}, 1000), updated_at = #{updatedAt}, "
            + "version = version + 1 where id = #{id}::uuid")
    int updateReconciliationDiagnostic(@Param("id") UUID id,
                                       @Param("requestId") String requestId,
                                       @Param("providerCode") String providerCode,
                                       @Param("errorCode") String errorCode,
                                       @Param("errorMessage") String errorMessage,
                                       @Param("updatedAt") Instant updatedAt);

    @Update("update chatapp_broadcasts set status = 'SUBMISSION_UNKNOWN', "
            + "error_code = 'CHATAPP_BROADCAST_SUBMISSION_UNKNOWN', "
            + "error_message = 'CHATAPP_BROADCAST_SUBMISSION_UNKNOWN', "
            + "updated_at = #{updatedAt}, version = version + 1 "
            + "where id = #{id}::uuid and status in ('QUEUED', 'SUBMITTING') "
            + "and provider_group_message_id is null")
    int markSubmissionUnknownIfUnresolved(@Param("id") UUID id,
                                          @Param("updatedAt") Instant updatedAt);
}
