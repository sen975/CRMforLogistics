package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.TemplateMediaAssetEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface TemplateMediaAssetMapper extends BaseMapper<TemplateMediaAssetEntity> {

    @Insert("""
            insert into template_media_assets
                (id, channel_account_id, client_request_id, media_format, content_type, size_bytes, sha256,
                 asset_status, created_by_user_id, trace_id, started_at, created_at, updated_at)
            values (#{id}, #{channelAccountId}, #{clientRequestId}, #{mediaFormat}, #{contentType},
                    #{sizeBytes}, #{sha256}, 'PROCESSING', #{createdByUserId}, #{traceId},
                    #{startedAt}, #{createdAt}, #{updatedAt})
            on conflict (channel_account_id, client_request_id) do nothing
            """)
    int insertProcessing(TemplateMediaAssetEntity asset);

    @Select("select * from template_media_assets where channel_account_id = #{accountId}::uuid "
            + "and client_request_id = #{clientRequestId} limit 1")
    Optional<TemplateMediaAssetEntity> findByClientRequestId(@Param("accountId") UUID accountId,
                                                              @Param("clientRequestId") String clientRequestId);

    @Select("select * from template_media_assets where id = #{assetId}::uuid "
            + "and channel_account_id = #{accountId}::uuid limit 1")
    Optional<TemplateMediaAssetEntity> findByIdAndChannelAccountId(@Param("assetId") UUID assetId,
                                                                   @Param("accountId") UUID accountId);

    @Update("update template_media_assets set asset_status = 'ATTACHED', attached_at = #{attachedAt}, "
            + "updated_at = #{attachedAt} "
            + "where id = #{id}::uuid and asset_status = 'UPLOADED'")
    int markAttached(@Param("id") UUID id, @Param("attachedAt") Instant attachedAt);

    @Update("update template_media_assets set asset_status = 'ORPHANED', updated_at = now() "
            + "where id = #{id}::uuid and asset_status in ('UPLOADED', 'ATTACHMENT_UNKNOWN')")
    int markOrphaned(@Param("id") UUID id);

    @Update("update template_media_assets set provider_object_key = #{objectKey}, provider_url = #{url}, "
            + "asset_status = 'UPLOADED', updated_at = #{updatedAt} where id = #{id}::uuid "
            + "and asset_status = 'PROCESSING'")
    int markUploaded(@Param("id") UUID id, @Param("objectKey") String objectKey, @Param("url") String url,
                     @Param("updatedAt") Instant updatedAt);

    @Update("update template_media_assets set asset_status = 'FAILED', error_code = #{errorCode}, "
            + "error_message = #{errorMessage}, updated_at = #{updatedAt} where id = #{id}::uuid "
            + "and asset_status = 'PROCESSING'")
    int markFailed(@Param("id") UUID id, @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage, @Param("updatedAt") Instant updatedAt);

    @Update("update template_media_assets set asset_status = 'SUBMISSION_UNKNOWN', error_code = #{errorCode}, "
            + "error_message = #{errorMessage}, updated_at = #{updatedAt} where id = #{id}::uuid "
            + "and asset_status = 'PROCESSING'")
    int markUnknown(@Param("id") UUID id, @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage, @Param("updatedAt") Instant updatedAt);

    @Update("update template_media_assets set asset_status = 'SUBMISSION_UNKNOWN', "
            + "error_code = 'TEMPLATE_MEDIA_SUBMISSION_UNKNOWN', "
            + "error_message = 'Upload result is unknown after process interruption', updated_at = #{updatedAt} "
            + "where id = #{id}::uuid and asset_status = 'PROCESSING' and started_at <= #{cutoff}")
    int expireProcessing(@Param("id") UUID id, @Param("cutoff") Instant cutoff,
                         @Param("updatedAt") Instant updatedAt);
}
