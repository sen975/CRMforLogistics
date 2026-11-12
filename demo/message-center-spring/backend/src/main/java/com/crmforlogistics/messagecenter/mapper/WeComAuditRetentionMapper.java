package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.channel.wecom.WeComAuditRetentionStateEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WeComAuditRetentionMapper {

    @Select("SELECT pg_try_advisory_xact_lock(hashtextextended('wecom-audit-retention', 0))")
    boolean tryAcquireBatchLock();

    @Delete("""
            WITH doomed AS (
                SELECT id
                FROM wecom_viewer_audit
                WHERE occurred_at < #{cutoff}
                ORDER BY occurred_at, id
                LIMIT #{limit}
            )
            DELETE FROM wecom_viewer_audit audit
            USING doomed
            WHERE audit.id = doomed.id
            """)
    int deleteExpiredViewer(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    @Delete("""
            WITH doomed AS (
                SELECT audit.id
                FROM wecom_authorization_audit audit
                WHERE audit.occurred_at < #{cutoff}
                  AND NOT EXISTS (
                      SELECT 1
                      FROM wecom_authorization_audit open_phase
                      WHERE open_phase.event_id = audit.event_id
                        AND open_phase.attempt = audit.attempt
                        AND open_phase.result IN ('accepted', 'pending')
                        AND NOT EXISTS (
                            SELECT 1
                            FROM wecom_authorization_audit terminal
                            WHERE terminal.event_id = open_phase.event_id
                              AND terminal.attempt = open_phase.attempt
                              AND terminal.result IN ('succeeded', 'failed')
                        )
                  )
                ORDER BY audit.occurred_at, audit.id
                LIMIT #{limit}
            )
            DELETE FROM wecom_authorization_audit audit
            USING doomed
            WHERE audit.id = doomed.id
            """)
    int deleteExpiredAuthorization(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    @Delete("""
            WITH doomed AS (
                SELECT audit.id
                FROM wecom_api_audit audit
                WHERE audit.occurred_at < #{cutoff}
                  AND NOT (
                      audit.result = 'accepted'
                      AND NOT EXISTS (
                          SELECT 1
                          FROM wecom_api_audit terminal
                          WHERE terminal.operation_id = audit.operation_id
                            AND terminal.result IN ('success', 'failed', 'denied')
                      )
                  )
                ORDER BY audit.occurred_at, audit.id
                LIMIT #{limit}
            )
            DELETE FROM wecom_api_audit audit
            USING doomed
            WHERE audit.id = doomed.id
            """)
    int deleteExpiredApi(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    @Insert("""
            INSERT INTO wecom_audit_retention_state
                (stream, last_started_at, last_completed_at, deleted_count,
                 status, error_code, updated_at)
            VALUES
                (#{stream}, #{lastStartedAt}, #{lastCompletedAt}, #{deletedCount},
                 #{status}, #{errorCode}, #{updatedAt})
            ON CONFLICT (stream) DO UPDATE SET
                last_started_at = EXCLUDED.last_started_at,
                last_completed_at = EXCLUDED.last_completed_at,
                deleted_count = EXCLUDED.deleted_count,
                status = EXCLUDED.status,
                error_code = EXCLUDED.error_code,
                updated_at = EXCLUDED.updated_at
            """)
    int upsertState(WeComAuditRetentionStateEntity state);

    @Select("SELECT * FROM wecom_audit_retention_state WHERE stream = #{stream}")
    WeComAuditRetentionStateEntity findState(@Param("stream") String stream);
}
