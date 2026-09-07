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
public interface AiTopicReviewMapper {
    String SOURCE_UNION = "select m.id, ci.id contact_identity_id, 'MESSAGE' source_type, ca.channel_type, m.occurred_at, m.direction, coalesce(m.subject,'') subject, coalesce(m.body_text,'') text, " +
            "(assigned.topic_id is null or (assigned_topic.owner_type='CONTACT' and coalesce(assigned_topic.owner_id, assigned_topic.contact_id)=ci.contact_id and assigned_topic.status='READY')) selectable, " +
            "case when assigned.topic_id is null or (assigned_topic.owner_type='CONTACT' and coalesce(assigned_topic.owner_id, assigned_topic.contact_id)=ci.contact_id and assigned_topic.status='READY') then null else 'TOPIC_REVIEW_SOURCE_LOCKED' end excluded_reason, assigned.topic_id assigned_topic_id, assigned_topic.title assigned_topic_title " +
            "from messages m join conversations cv on cv.id=m.conversation_id join contact_identities ci on ci.id=cv.contact_identity_id " +
            "join channel_accounts ca on ca.id=m.channel_account_id left join ai_topic_items assigned on assigned.message_id=m.id left join ai_topics assigned_topic on assigned_topic.id=assigned.topic_id " +
            "where ci.contact_id=#{contactId}::uuid and ca.channel_type in ('chatapp','email','whatsapp') " +
            "<if test=\"contactIdentityId != null\">and ci.id=#{contactIdentityId}::uuid </if>" +
            "union all " +
            "select cr.id, ci.id, 'CALL_RECORD', 'phone', cr.occurred_at, cr.direction, '', trim(coalesce(cr.transcription_result_original_text,'') || E'\\n' || coalesce(cr.note,'')), " +
            "(assigned.topic_id is null or (assigned_topic.owner_type='CONTACT' and coalesce(assigned_topic.owner_id, assigned_topic.contact_id)=ci.contact_id and assigned_topic.status='READY')), " +
            "case when assigned.topic_id is null or (assigned_topic.owner_type='CONTACT' and coalesce(assigned_topic.owner_id, assigned_topic.contact_id)=ci.contact_id and assigned_topic.status='READY') then null else 'TOPIC_REVIEW_SOURCE_LOCKED' end, assigned.topic_id, assigned_topic.title " +
            "from call_records cr join contact_identities ci on ci.channel_type='phone' and cr.contact_anchor_point_id=ci.channel_type || ':' || ci.normalized_value " +
            "left join ai_topic_items assigned on assigned.call_record_id=cr.id left join ai_topics assigned_topic on assigned_topic.id=assigned.topic_id " +
            "where ci.contact_id=#{contactId}::uuid <if test=\"contactIdentityId != null\">and ci.id=#{contactIdentityId}::uuid </if>" +
            "union all " +
            "select j.id, ci.id, 'WECOM_SUMMARY', 'wecom', to_timestamp(j.send_time), 'inbound', '', coalesce(j.summary,''), " +
            "(j.status='COMPLETED' and btrim(coalesce(j.summary,''))&lt;&gt;'' and (assigned.topic_id is null or (assigned_topic.owner_type='CONTACT' and coalesce(assigned_topic.owner_id, assigned_topic.contact_id)=ci.contact_id and assigned_topic.status='READY'))) selectable, " +
            "case when j.status&lt;&gt;'COMPLETED' or btrim(coalesce(j.summary,''))='' then coalesce(nullif(j.last_error_code,''),'WECOM_SUMMARY_NOT_COMPLETED') when assigned.topic_id is not null and not (assigned_topic.owner_type='CONTACT' and coalesce(assigned_topic.owner_id, assigned_topic.contact_id)=ci.contact_id and assigned_topic.status='READY') then 'TOPIC_REVIEW_SOURCE_LOCKED' else null end excluded_reason, assigned.topic_id, assigned_topic.title " +
            "from wecom_message_summary_jobs j join wecom_source_conversations sc on sc.id=j.source_conversation_id and sc.conversation_type='DIRECT' " +
            "join contact_identities ci on ci.id=sc.contact_identity_id left join ai_topic_items assigned on assigned.wecom_message_summary_job_id=j.id left join ai_topics assigned_topic on assigned_topic.id=assigned.topic_id " +
            "where ci.contact_id=#{contactId}::uuid <if test=\"contactIdentityId != null\">and ci.id=#{contactIdentityId}::uuid </if>";

    @Select("<script>select * from (" + SOURCE_UNION +
            ") sources where (#{fromAt}::timestamptz is null or occurred_at >= #{fromAt}) and (#{toAt}::timestamptz is null or occurred_at &lt; #{toAt}) " +
            "order by occurred_at, id limit #{limit}</script>")
    List<SourceRow> listSources(@Param("contactId") UUID contactId,
                                @Param("contactIdentityId") UUID contactIdentityId,
                                @Param("fromAt") Instant fromAt,
                                @Param("toAt") Instant toAt, @Param("limit") int limit);

    @Select("<script>select * from (" + SOURCE_UNION + ") sources where id in " +
            "<foreach item=\"sourceId\" collection=\"sourceIds\" open=\"(\" separator=\",\" close=\")\">#{sourceId}::uuid</foreach> " +
            "order by occurred_at, id</script>")
    List<SourceRow> listSourcesByIds(@Param("contactId") UUID contactId,
                                     @Param("contactIdentityId") UUID contactIdentityId,
                                     @Param("sourceIds") List<UUID> sourceIds);

    @Select("<script>select * from (" +
            "select m.id, null::uuid contact_identity_id, 'MESSAGE' source_type, i.channel_type, i.occurred_at, m.direction, coalesce(m.subject,'') subject, coalesce(m.body_text,'') text, true selectable, null::varchar excluded_reason " +
            "from ai_topic_items i join messages m on m.id=i.message_id where i.topic_id in " +
            "<foreach item=\"topicId\" collection=\"topicIds\" open=\"(\" separator=\",\" close=\")\">#{topicId}::uuid</foreach> " +
            "union all " +
            "select cr.id, null::uuid, 'CALL_RECORD', i.channel_type, i.occurred_at, cr.direction, '', trim(coalesce(cr.transcription_result_original_text,'') || E'\\n' || coalesce(cr.note,'')), true, null::varchar " +
            "from ai_topic_items i join call_records cr on cr.id=i.call_record_id where i.topic_id in " +
            "<foreach item=\"topicId\" collection=\"topicIds\" open=\"(\" separator=\",\" close=\")\">#{topicId}::uuid</foreach> " +
            "union all " +
            "select j.id, null::uuid, 'WECOM_SUMMARY', i.channel_type, i.occurred_at, 'inbound', '', j.summary, true, null::varchar " +
            "from ai_topic_items i join wecom_message_summary_jobs j on j.id=i.wecom_message_summary_job_id where i.topic_id in " +
            "<foreach item=\"topicId\" collection=\"topicIds\" open=\"(\" separator=\",\" close=\")\">#{topicId}::uuid</foreach>" +
            ") sources order by occurred_at, id</script>")
    List<SourceRow> listTopicSources(@Param("topicIds") List<UUID> topicIds);

    @Insert("insert into ai_topic_review_previews (id, contact_id, source_fingerprint, source_snapshot, assignment_snapshot, expected_versions, from_at, to_at, created_by_user_id, expires_at) values (#{id}::uuid, #{contactId}::uuid, #{fingerprint}, cast(#{snapshot} as jsonb), cast(#{assignments} as jsonb), cast(#{expectedVersions} as jsonb), #{fromAt}, #{toAt}, #{userId}::uuid, #{expiresAt})")
    int insertPreview(@Param("id") UUID id, @Param("contactId") UUID contactId, @Param("fingerprint") String fingerprint,
                      @Param("snapshot") String snapshot, @Param("assignments") String assignments, @Param("expectedVersions") String expectedVersions,
                      @Param("fromAt") Instant fromAt, @Param("toAt") Instant toAt,
                      @Param("userId") UUID userId, @Param("expiresAt") Instant expiresAt);

    @Select("select id, contact_id, source_fingerprint, source_snapshot, assignment_snapshot, expected_versions, from_at, to_at, status, result_topic_ids, created_by_user_id, expires_at from ai_topic_review_previews where id=#{id}::uuid")
    PreviewRow findPreview(@Param("id") UUID id);

    @Select("select id, contact_id, source_fingerprint, source_snapshot, assignment_snapshot, expected_versions, from_at, to_at, status, result_topic_ids, created_by_user_id, expires_at from ai_topic_review_previews where created_by_user_id=#{userId}::uuid and idempotency_key=#{idempotencyKey} and status='APPLIED' order by created_at desc limit 1")
    PreviewRow findByIdempotency(@Param("userId") UUID userId, @Param("idempotencyKey") String idempotencyKey);

    @Update("update ai_topic_review_previews set status='APPLIED', result_topic_ids=cast(#{topicIds} as jsonb), idempotency_key=#{idempotencyKey}, applied_at=now() where id=#{id}::uuid and status='PENDING' and expires_at > now()")
    int markApplied(@Param("id") UUID id, @Param("topicIds") String topicIds, @Param("idempotencyKey") String idempotencyKey);

    @Insert("insert into ai_topic_fusion_previews (id, owner_type, owner_id, topic_ids, result_snapshot, expected_versions, created_by_user_id, expires_at) values (#{id}::uuid, #{ownerType}, #{ownerId}::uuid, cast(#{topicIds} as jsonb), cast(#{resultSnapshot} as jsonb), cast(#{expectedVersions} as jsonb), #{userId}::uuid, #{expiresAt})")
    int insertFusionPreview(@Param("id") UUID id, @Param("ownerType") String ownerType,
                            @Param("ownerId") UUID ownerId, @Param("topicIds") String topicIds,
                            @Param("resultSnapshot") String resultSnapshot,
                            @Param("expectedVersions") String expectedVersions,
                            @Param("userId") UUID userId, @Param("expiresAt") Instant expiresAt);

    @Select("select * from ai_topic_fusion_previews where id=#{id}::uuid")
    FusionPreviewRow findFusionPreview(@Param("id") UUID id);

    @Select("select * from ai_topic_fusion_previews where created_by_user_id=#{userId}::uuid and idempotency_key=#{idempotencyKey} order by created_at desc limit 1")
    FusionPreviewRow findFusionByIdempotency(@Param("userId") UUID userId,
                                              @Param("idempotencyKey") String idempotencyKey);

    @Update("update ai_topic_fusion_previews set status='APPLIED', result_topic_id=#{resultTopicId}::uuid, idempotency_key=#{idempotencyKey}, applied_at=now() where id=#{id}::uuid and status='PENDING' and expires_at > now()")
    int markFusionApplied(@Param("id") UUID id, @Param("resultTopicId") UUID resultTopicId,
                          @Param("idempotencyKey") String idempotencyKey);

    record SourceRow(UUID id, UUID contactIdentityId, String sourceType, String channelType,
                     Instant occurredAt, String direction, String subject, String text,
                     boolean selectable, String excludedReason, UUID assignedTopicId,
                     String assignedTopicTitle) {
        public SourceRow(UUID id, UUID contactIdentityId, String sourceType, String channelType,
                         Instant occurredAt, String direction, String subject, String text,
                         boolean selectable, String excludedReason) {
            this(id, contactIdentityId, sourceType, channelType, occurredAt, direction, subject, text,
                    selectable, excludedReason, null, null);
        }
        public SourceRow(UUID id, UUID contactIdentityId, String sourceType, String channelType,
                         Instant occurredAt, String direction, String subject, String text) {
            this(id, contactIdentityId, sourceType, channelType, occurredAt, direction, subject, text,
                    true, null, null, null);
        }
        public SourceRow(UUID id, String sourceType, String channelType, Instant occurredAt,
                         String subject, String text) {
            this(id, null, sourceType, channelType, occurredAt, "", subject, text, true, null, null, null);
        }
    }
    record PreviewRow(UUID id, UUID contactId, String sourceFingerprint, String sourceSnapshot, String assignmentSnapshot, String expectedVersions,
                      Instant fromAt, Instant toAt, String status, String resultTopicIds,
                      UUID createdByUserId, Instant expiresAt) {}
    record FusionPreviewRow(UUID id, String ownerType, UUID ownerId, String topicIds,
                            String resultSnapshot, String expectedVersions, String status,
                            UUID resultTopicId, String idempotencyKey, UUID createdByUserId,
                            Instant createdAt, Instant expiresAt, Instant appliedAt) {}
}
