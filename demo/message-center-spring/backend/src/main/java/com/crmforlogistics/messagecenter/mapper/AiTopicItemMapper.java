package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.AiTopicItemEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Mapper
public interface AiTopicItemMapper extends BaseMapper<AiTopicItemEntity> {
    @Insert("insert into ai_topic_items (id, topic_id, message_id, call_record_id, wecom_message_summary_job_id, occurred_at, channel_type) values (gen_random_uuid(), #{topicId}::uuid, #{messageId}::uuid, #{callRecordId}::uuid, #{wecomMessageSummaryJobId}::uuid, #{occurredAt}, #{channelType}) on conflict do nothing")
    int insertIfAbsent(UUID topicId, UUID messageId, UUID callRecordId, UUID wecomMessageSummaryJobId,
                       java.time.Instant occurredAt, String channelType);

    default int insertIfAbsent(UUID topicId, UUID messageId, UUID callRecordId,
                               java.time.Instant occurredAt, String channelType) {
        return insertIfAbsent(topicId, messageId, callRecordId, null, occurredAt, channelType);
    }

    @Select("select * from ai_topic_items where topic_id=#{topicId}::uuid order by occurred_at, id")
    List<AiTopicItemEntity> listByTopic(UUID topicId);

    @Select("select * from ai_topic_items where topic_id in (select id from ai_topics where contact_id=#{contactId}::uuid and status='READY') order by occurred_at, id")
    List<AiTopicItemEntity> listByContact(UUID contactId);

    @Select("select i.* from ai_topic_items i join ai_topics t on t.id=i.topic_id where t.contact_id=#{contactId}::uuid")
    List<AiTopicItemEntity> listAssignedByContact(UUID contactId);

    default com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.AssignedSourceIds listAssignedSourceIds(UUID contactId) {
        Set<UUID> messageIds = new LinkedHashSet<>();
        Set<UUID> callRecordIds = new LinkedHashSet<>();
        for (AiTopicItemEntity item : listAssignedByContact(contactId)) {
            if (item.getMessageId() != null) messageIds.add(item.getMessageId());
            if (item.getCallRecordId() != null) callRecordIds.add(item.getCallRecordId());
        }
        return new com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.AssignedSourceIds(Set.copyOf(messageIds), Set.copyOf(callRecordIds));
    }
}
