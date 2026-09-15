package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.WhatsAppAccountAssignmentAuditEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface WhatsAppAccountAssignmentAuditMapper extends BaseMapper<WhatsAppAccountAssignmentAuditEntity> {
    @Select("select id, channel_account_id, previous_owner_user_id, next_owner_user_id, " +
            "actor_user_id, action, reason, created_at from whatsapp_account_assignment_audits " +
            "where channel_account_id = #{accountId}::uuid order by created_at desc, id desc")
    List<WhatsAppAccountAssignmentAuditEntity> findByAccountId(@Param("accountId") UUID accountId);
}
