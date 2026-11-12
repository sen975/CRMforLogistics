package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper {

    @Insert("insert into audit_logs (id, actor_user_id, action, resource_type, resource_id, "
            + "before_summary_jsonb, after_summary_jsonb, result, ip_address, user_agent, trace_id, occurred_at) "
            + "values (#{id}::uuid, #{actorUserId}::uuid, #{action}, #{resourceType}, #{resourceId}::uuid, "
            + "cast(#{beforeSummaryJsonb} as jsonb), cast(#{afterSummaryJsonb} as jsonb), #{result}, "
            + "cast(#{ipAddress} as inet), #{userAgent}, #{traceId}, coalesce(#{occurredAt}, now()))")
    int insert(AuditLogEntity auditLog);
}
