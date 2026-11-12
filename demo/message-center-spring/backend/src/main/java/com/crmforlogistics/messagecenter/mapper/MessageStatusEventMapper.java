package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.MessageStatusEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageStatusEventMapper extends BaseMapper<MessageStatusEventEntity> {
    @Insert("insert into message_status_events (id, message_id, status, occurred_at, provider_event_id, " +
            "reason_code, reason_message, metadata_jsonb) " +
            "values (#{id}::uuid, #{messageId}::uuid, #{status}, #{occurredAt}, #{providerEventId}, " +
            "#{reasonCode}, #{reasonMessage}, cast(#{metadataJsonb} as jsonb)) " +
            "on conflict do nothing")
    int insertIgnore(MessageStatusEventEntity event);
}
