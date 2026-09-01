package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface AiTopicOwnerActivityMapper {
    @Insert("INSERT INTO ai_topic_owner_activity(owner_type, owner_id, latest_event_at, quiet_deadline, activity_version, status, updated_at) "
            + "VALUES(#{ownerType}, #{ownerId}, #{occurredAt}, #{deadline}, 1, 'WAITING', now()) "
            + "ON CONFLICT(owner_type, owner_id) DO UPDATE SET latest_event_at = GREATEST(ai_topic_owner_activity.latest_event_at, EXCLUDED.latest_event_at), "
            + "quiet_deadline = GREATEST(ai_topic_owner_activity.quiet_deadline, EXCLUDED.quiet_deadline), "
            + "activity_version = ai_topic_owner_activity.activity_version + CASE WHEN EXCLUDED.latest_event_at > ai_topic_owner_activity.latest_event_at "
            + "OR EXCLUDED.quiet_deadline > ai_topic_owner_activity.quiet_deadline THEN 1 ELSE 0 END, status='WAITING', updated_at=now()")
    int upsertActivity(@Param("ownerType") String ownerType, @Param("ownerId") UUID ownerId,
                       @Param("occurredAt") Instant occurredAt, @Param("deadline") Instant deadline);

    @Select("SELECT owner_type, owner_id, latest_event_at, quiet_deadline, activity_version FROM ai_topic_owner_activity "
            + "WHERE quiet_deadline <= #{now} AND status='WAITING' AND (lease_until IS NULL OR lease_until <= #{now}) "
            + "ORDER BY quiet_deadline, owner_id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<ActivityRow> listDue(@Param("now") Instant now, @Param("limit") int limit);

    @Update("UPDATE ai_topic_owner_activity SET status='LEASED', lease_owner=#{leaseOwner}, lease_until=#{leaseUntil}, updated_at=now() "
            + "WHERE owner_type=#{ownerType} AND owner_id=#{ownerId} AND status='WAITING' "
            + "AND activity_version=#{activityVersion} AND quiet_deadline <= #{now}")
    int lease(@Param("ownerType") String ownerType, @Param("ownerId") UUID ownerId,
              @Param("activityVersion") long activityVersion, @Param("leaseOwner") String leaseOwner,
              @Param("leaseUntil") Instant leaseUntil, @Param("now") Instant now);

    @Update("UPDATE ai_topic_owner_activity SET status='GENERATING', lease_owner=NULL, lease_until=NULL, updated_at=now() "
            + "WHERE owner_type=#{ownerType} AND owner_id=#{ownerId} AND status='LEASED' "
            + "AND activity_version=#{activityVersion} AND lease_owner=#{leaseOwner}")
    int markGenerating(@Param("ownerType") String ownerType, @Param("ownerId") UUID ownerId,
                       @Param("activityVersion") long activityVersion, @Param("leaseOwner") String leaseOwner);

    @Update("UPDATE ai_topic_owner_activity SET status='WAITING', lease_owner=NULL, lease_until=NULL, updated_at=now() "
            + "WHERE owner_type=#{ownerType} AND owner_id=#{ownerId} AND lease_owner=#{leaseOwner} AND activity_version=#{activityVersion}")
    int release(@Param("ownerType") String ownerType, @Param("ownerId") UUID ownerId,
                @Param("activityVersion") long activityVersion, @Param("leaseOwner") String leaseOwner);

    @Select("""
            WITH candidates AS (
                SELECT 'CONTACT'::varchar AS owner_type, ci.contact_id AS owner_id, m.occurred_at
                FROM messages m
                JOIN conversations cv ON cv.id = m.conversation_id
                JOIN contact_identities ci ON ci.id = cv.contact_identity_id AND ci.deleted_at IS NULL
                JOIN channel_accounts ca ON ca.id = m.channel_account_id
                WHERE ca.channel_type IN ('chatapp', 'email')
                  AND NOT EXISTS (SELECT 1 FROM ai_topic_items i WHERE i.message_id = m.id)
                UNION ALL
                SELECT 'CONTACT'::varchar AS owner_type, ci.contact_id AS owner_id, cr.occurred_at
                FROM call_records cr
                JOIN contact_identities ci ON ci.channel_type = 'phone' AND ci.deleted_at IS NULL
                    AND ci.normalized_value = substring(cr.contact_anchor_point_id from 7)
                WHERE cr.contact_anchor_point_id LIKE 'phone:%'
                  AND (cr.transcription_state = 'completed' OR coalesce(btrim(cr.note), '') <> '')
                  AND NOT EXISTS (SELECT 1 FROM ai_topic_items i WHERE i.call_record_id = cr.id)
                UNION ALL
                SELECT 'CONTACT'::varchar AS owner_type, ci.contact_id AS owner_id,
                    to_timestamp(j.send_time) AS occurred_at
                FROM wecom_message_summary_jobs j
                JOIN wecom_source_conversations sc ON sc.id = j.source_conversation_id
                    AND sc.conversation_type = 'DIRECT'
                JOIN contact_identities ci ON ci.id = sc.contact_identity_id AND ci.deleted_at IS NULL
                WHERE j.status = 'COMPLETED' AND coalesce(btrim(j.summary), '') <> ''
                  AND NOT EXISTS (SELECT 1 FROM ai_topic_items i
                      WHERE i.wecom_message_summary_job_id = j.id)
                UNION ALL
                SELECT 'WECOM_GROUP'::varchar AS owner_type, j.source_conversation_id AS owner_id,
                    to_timestamp(j.send_time) AS occurred_at
                FROM wecom_message_summary_jobs j
                JOIN wecom_source_conversations sc ON sc.id = j.source_conversation_id
                    AND sc.conversation_type = 'GROUP'
                WHERE j.status = 'COMPLETED' AND coalesce(btrim(j.summary), '') <> ''
                  AND NOT EXISTS (SELECT 1 FROM ai_topic_items i
                      WHERE i.wecom_message_summary_job_id = j.id)
            ), owners_without_activity AS (
                SELECT c.owner_type, c.owner_id, max(c.occurred_at) AS occurred_at
                FROM candidates c
                WHERE c.owner_id IS NOT NULL AND c.occurred_at IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM ai_topic_owner_activity a
                      WHERE a.owner_type = c.owner_type AND a.owner_id = c.owner_id
                  )
                GROUP BY c.owner_type, c.owner_id
            )
            SELECT owner_type, owner_id, occurred_at
            FROM owners_without_activity
            ORDER BY occurred_at, owner_type, owner_id
            LIMIT #{limit}
            """)
    List<ActivityCandidate> listHistoricalCandidates(@Param("limit") int limit);

    record ActivityRow(String ownerType, UUID ownerId, Instant latestEventAt, Instant quietDeadline,
                       long activityVersion) {}

    record ActivityCandidate(String ownerType, UUID ownerId, Instant occurredAt) {}
}
