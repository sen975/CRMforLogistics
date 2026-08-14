package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.channel.wecom.WeComViewerAuditEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WeComViewerAuditMapper extends BaseMapper<WeComViewerAuditEntity> {
}
