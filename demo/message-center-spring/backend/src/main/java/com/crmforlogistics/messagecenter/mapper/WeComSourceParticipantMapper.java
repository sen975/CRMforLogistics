package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.WeComSourceParticipantEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import java.util.UUID;

@Mapper
public interface WeComSourceParticipantMapper extends BaseMapper<WeComSourceParticipantEntity> {
    @Insert("insert into wecom_source_conversation_participants (source_conversation_id, party_id, "
            + "participant_status, first_observed_at, last_observed_at) values (#{conversationId}::uuid, "
            + "#{partyId}::uuid, 'OBSERVED', now(), now()) on conflict (source_conversation_id, party_id) "
            + "do update set participant_status = 'OBSERVED', last_observed_at = now()")
    int observe(@Param("conversationId") UUID conversationId, @Param("partyId") UUID partyId);
}
