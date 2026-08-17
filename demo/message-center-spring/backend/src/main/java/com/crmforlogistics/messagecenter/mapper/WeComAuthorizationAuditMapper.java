package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.channel.wecom.WeComAuthorizationAuditEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WeComAuthorizationAuditMapper extends BaseMapper<WeComAuthorizationAuditEntity> {

    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{eventId}, 0))")
    Object lockEvent(@Param("eventId") String eventId);

    @Select("SELECT count(*) FROM wecom_authorization_audit WHERE event_id = #{eventId} AND result = 'succeeded'")
    int countSucceeded(@Param("eventId") String eventId);

    @Select("SELECT coalesce(max(attempt), 0) FROM wecom_authorization_audit WHERE event_id = #{eventId}")
    int maxAttempt(@Param("eventId") String eventId);

    @Select("SELECT DISTINCT ON (audit_row.event_id, audit_row.attempt) audit_row.* "
            + "FROM wecom_authorization_audit audit_row "
            + "WHERE audit_row.event_id = #{eventId} AND audit_row.result IN ('accepted', 'pending') "
            + "AND NOT EXISTS (SELECT 1 FROM wecom_authorization_audit final "
            + "WHERE final.event_id = audit_row.event_id "
            + "AND final.attempt = audit_row.attempt "
            + "AND final.result IN ('succeeded', 'failed')) "
            + "ORDER BY audit_row.event_id, audit_row.attempt DESC, "
            + "CASE audit_row.result WHEN 'pending' THEN 1 ELSE 0 END DESC, audit_row.occurred_at DESC LIMIT 1")
    WeComAuthorizationAuditEntity findOpenAttempt(@Param("eventId") String eventId);

    @Select("SELECT DISTINCT ON (audit_row.event_id, audit_row.attempt) audit_row.* "
            + "FROM wecom_authorization_audit audit_row "
            + "WHERE audit_row.event_id IS NOT NULL AND audit_row.result IN ('accepted', 'pending') "
            + "AND NOT EXISTS (SELECT 1 FROM wecom_authorization_audit final "
            + "WHERE final.event_id = audit_row.event_id AND final.attempt = audit_row.attempt "
            + "AND final.result IN ('succeeded', 'failed')) "
            + "ORDER BY audit_row.event_id, audit_row.attempt, "
            + "CASE audit_row.result WHEN 'pending' THEN 1 ELSE 0 END DESC, audit_row.occurred_at DESC")
    List<WeComAuthorizationAuditEntity> findOpenAttempts();
}
