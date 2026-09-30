package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.TemplateMediaAssetEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
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

    /**
     * 按账号列出<b>还能被模板引用</b>的素材，最近的在前（素材库的读口）。
     *
     * <h2>{@code asset_status = 'UPLOADED'} 这个口径不是这里定的</h2>
     * 它出自 {@code WhatsAppTemplateApplicationService.prepareMedia}：只有上传完成、且<b>还没挂到
     * 别的模板上</b>的素材才能被新模板引用。{@code ATTACHED} 看着像「传好了、只是上次用掉了」，
     * 但建模板时照样会被拒；{@code ORPHANED} 更不用说。列出来等于让调用方发起一次注定失败的
     * 调用，再把失败转述成一个用户无从下手的错误码 —— 所以过滤放在 SQL 里，读口出来的每一条
     * 都是当场能用的。
     *
     * <p>注解的值必须是编译期常量，拼不进枚举名，所以这里写的是字面量 ——
     * 改 {@code MediaAssetStatus} 的取值时，把本方法、
     * {@code WhatsAppTemplateApplicationService#prepareMedia} 与
     * {@code TemplateMediaProvider#USABLE_STATUS} 三处一起看。
     *
     * <p>去哪查正好走 {@code template_media_assets} 上那条
     * {@code (channel_account_id, asset_status, created_at desc)} 专用索引 ——
     * 过滤与排序都在索引里，不需要回表排序。
     */
    @Select("select * from template_media_assets where channel_account_id = #{accountId}::uuid "
            + "and asset_status = 'UPLOADED' order by created_at desc limit #{limit}")
    List<TemplateMediaAssetEntity> findUsableByChannelAccountId(@Param("accountId") UUID accountId,
                                                                @Param("limit") int limit);

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
