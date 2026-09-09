package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.ChatAppCapabilityResultEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatAppCapabilityResultMapper extends BaseMapper<ChatAppCapabilityResultEntity> {

    @Select("select count(distinct action) = 3 from chatapp_capability_results "
            + "where phase = 'READ_ONLY' and status = 'VERIFIED' and report_id = "
            + "(select report_id from chatapp_capability_results where phase = 'READ_ONLY' "
            + "order by tested_at desc, created_at desc limit 1)")
    boolean latestReadOnlyReady();

    @Select("select id, report_id, channel_account_id, provider_scope_id, phase, action, status, "
            + "provider_request_id, diagnostic_code, diagnostic_message, tested_phone_last4, tested_at, created_at "
            + "from chatapp_capability_results where report_id = "
            + "(select report_id from chatapp_capability_results order by tested_at desc, created_at desc limit 1) "
            + "order by created_at asc, id asc")
    List<ChatAppCapabilityResultEntity> findLatestReport();
}
