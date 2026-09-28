package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.WhatsAppCallbackAuditEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WhatsAppCallbackAuditMapper extends BaseMapper<WhatsAppCallbackAuditEntity> {
    @Insert("insert into whatsapp_cams_callback_audits "
            + "(id, provider_scope_id, channel_account_id, actor_user_id, level, action, "
            + "desired_url_host_hash, desired_url_path_hash, desired_url_https, expected_version, "
            + "result, error_code, provider_request_id) values "
            + "(#{id}::uuid, #{providerScopeId}::uuid, #{channelAccountId}::uuid, #{actorUserId}::uuid, "
            + "#{level}, #{action}, #{desiredUrlHostHash}, #{desiredUrlPathHash}, #{desiredUrlHttps}, "
            + "#{expectedVersion}, #{result}, #{errorCode}, #{providerRequestId})")
    int insert(WhatsAppCallbackAuditEntity entity);
}
