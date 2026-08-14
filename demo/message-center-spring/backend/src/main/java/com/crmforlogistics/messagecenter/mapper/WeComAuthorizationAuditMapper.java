package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationAuditEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WeComAuthorizationAuditMapper extends BaseMapper<WeComAuthorizationAuditEntity> {
}
