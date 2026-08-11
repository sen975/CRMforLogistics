package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WeComInstallationMapper extends BaseMapper<WeComInstallationEntity> {

    @Select("SELECT * FROM wecom_installations WHERE suite_id = #{suiteId} AND auth_corp_id = #{authCorpId} AND auth_status = 'ACTIVE' AND deleted_at IS NULL")
    WeComInstallationEntity findActive(String suiteId, String authCorpId);
}
