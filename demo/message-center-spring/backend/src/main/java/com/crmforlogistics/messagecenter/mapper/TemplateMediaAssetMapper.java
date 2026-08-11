package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.TemplateMediaAssetEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface TemplateMediaAssetMapper extends BaseMapper<TemplateMediaAssetEntity> {

    @Select("select * from template_media_assets where id = #{assetId}::uuid "
            + "and channel_account_id = #{accountId}::uuid limit 1")
    Optional<TemplateMediaAssetEntity> findByIdAndChannelAccountId(@Param("assetId") UUID assetId,
                                                                   @Param("accountId") UUID accountId);

    @Update("update template_media_assets set asset_status = 'ATTACHED', attached_at = #{attachedAt} "
            + "where id = #{id}::uuid and asset_status = 'UPLOADED'")
    int markAttached(@Param("id") UUID id, @Param("attachedAt") Instant attachedAt);

    @Update("update template_media_assets set asset_status = 'ORPHANED' "
            + "where id = #{id}::uuid and asset_status in ('UPLOADED', 'ATTACHMENT_UNKNOWN')")
    int markOrphaned(@Param("id") UUID id);
}
