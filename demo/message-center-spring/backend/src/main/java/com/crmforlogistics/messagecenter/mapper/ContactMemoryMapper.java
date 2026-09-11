package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.entity.ContactAiLabelEntity;
import com.crmforlogistics.messagecenter.entity.ContactAiLabelEvidenceEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryAttemptEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryFactEvidenceEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEvidenceEntity;
import com.crmforlogistics.messagecenter.entity.ContactProfileVersionEntity;
import com.crmforlogistics.messagecenter.entity.CallTranscriptRevisionEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.service.contactmemory.ContactMemoryModels;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface ContactMemoryMapper extends BaseMapper<ContactMemoryObservationEntity> {

    @Select("""
            select m.*
            from messages m
            join conversations cv on cv.id = m.conversation_id
            join contact_identities ci on ci.id = cv.contact_identity_id
            join contacts c on c.id = ci.contact_id
            where c.id = #{contactId}::uuid
              and c.created_by = #{ownerUserId}::uuid
              and c.deleted_at is null
              and ci.deleted_at is null
              and m.direction = 'inbound'
              and (#{after} is null or m.occurred_at &gt; #{after})
              and m.occurred_at &lt;= #{cutoff}
            order by m.occurred_at, m.id
            limit #{limit}
            """)
    List<MessageEntity> listInboundMessages(@Param("ownerUserId") UUID ownerUserId,
                                            @Param("contactId") UUID contactId,
                                            @Param("after") Instant after,
                                            @Param("cutoff") Instant cutoff,
                                            @Param("limit") int limit);

    @Select("""
            select m.*
            from messages m
            join conversations cv on cv.id = m.conversation_id
            join contact_identities ci on ci.id = cv.contact_identity_id
            join contacts c on c.id = ci.contact_id
            where c.id = #{contactId}::uuid
              and c.created_by = #{ownerUserId}::uuid
              and c.deleted_at is null
              and ci.deleted_at is null
              and m.direction = 'inbound'
              and (
                    #{afterOccurredAt} is null
                    or m.occurred_at > #{afterOccurredAt}
                    or (m.occurred_at = #{afterOccurredAt} and m.id > #{afterId}::uuid)
                  )
              and m.occurred_at <= #{cutoff}
            order by m.occurred_at, m.id
            limit #{limit}
            """)
    List<MessageEntity> listInboundMessagesByCursor(@Param("ownerUserId") UUID ownerUserId,
                                                     @Param("contactId") UUID contactId,
                                                     @Param("afterOccurredAt") Instant afterOccurredAt,
                                                     @Param("afterId") UUID afterId,
                                                     @Param("cutoff") Instant cutoff,
                                                     @Param("limit") int limit);

    @Select("""
            select *
            from contact_memory_observations
            where contact_id = #{contactId}::uuid
              and owner_user_id = #{ownerUserId}::uuid
              and status = 'CANDIDATE'
              and expires_at > now()
            order by observed_at desc, id
            limit #{limit}
            """)
    List<ContactMemoryObservationEntity> listActiveObservations(@Param("ownerUserId") UUID ownerUserId,
                                                                  @Param("contactId") UUID contactId,
                                                                  @Param("limit") int limit);

    @Select("""
            select ctr.*
            from call_transcript_revisions ctr
            join call_records cr on cr.current_revision_id = ctr.id
            join contacts c on c.id = cr.contact_id
            where cr.contact_id = #{contactId}::uuid
              and c.created_by = #{ownerUserId}::uuid
              and cr.transcription_state = 'completed'
            order by cr.occurred_at desc, ctr.edited_at desc, ctr.id desc
            limit #{limit}
            """)
    List<CallTranscriptRevisionEntity> listCallTranscripts(@Param("ownerUserId") UUID ownerUserId,
                                                            @Param("contactId") UUID contactId,
                                                            @Param("limit") int limit);

    @Select("""
            select *
            from contact_profile_versions
            where contact_id = #{contactId}::uuid
              and owner_user_id = #{ownerUserId}::uuid
              and is_current
            order by version desc
            limit 1
            """)
    ContactProfileVersionEntity findCurrentProfile(@Param("ownerUserId") UUID ownerUserId,
                                                   @Param("contactId") UUID contactId);

    @Select("""
            select *
            from contact_memory_facts
            where contact_id = #{contactId}::uuid
              and owner_user_id = #{ownerUserId}::uuid
              and status = 'ACTIVE'
            order by last_confirmed_at desc, id
            limit #{limit}
            """)
    List<ContactMemoryFactEntity> listActiveFacts(@Param("ownerUserId") UUID ownerUserId,
                                                  @Param("contactId") UUID contactId,
                                                  @Param("limit") int limit);

    @Select("""
            select *
            from contact_ai_labels
            where contact_id = #{contactId}::uuid
              and owner_user_id = #{ownerUserId}::uuid
              and status = 'ACTIVE'
            order by last_seen_at desc, id
            limit #{limit}
            """)
    List<ContactAiLabelEntity> listActiveLabels(@Param("ownerUserId") UUID ownerUserId,
                                                @Param("contactId") UUID contactId,
                                                @Param("limit") int limit);

    @Select("""
            select t.*
            from ai_topics t
            join contacts c on c.id = t.contact_id
            where t.contact_id = #{contactId}::uuid
              and c.created_by = #{ownerUserId}::uuid
              and t.status = 'READY'
            order by t.last_occurred_at desc, t.id
            limit #{limit}
            """)
    List<AiTopicEntity> listStableTopics(@Param("ownerUserId") UUID ownerUserId,
                                         @Param("contactId") UUID contactId,
                                         @Param("limit") int limit);

    default ContactMemoryModels.StableContext listStableContext(UUID ownerUserId,
                                                                  UUID contactId,
                                                                  int limit) {
        return new ContactMemoryModels.StableContext(
                findCurrentProfile(ownerUserId, contactId),
                listActiveFacts(ownerUserId, contactId, limit),
                listActiveLabels(ownerUserId, contactId, limit),
                listStableTopics(ownerUserId, contactId, limit));
    }

    @Insert("""
            insert into contact_memory_observations
                (id, contact_id, owner_user_id, category, normalized_key, observed_value,
                 polarity, confidence, status, source_cursor, generation_batch_id,
                 observed_at, expires_at, promoted_fact_id)
            values (coalesce(#{entity.id}, gen_random_uuid()), #{contactId}::uuid, #{ownerUserId}::uuid,
                    #{entity.category}, #{entity.normalizedKey}, #{entity.observedValue},
                    #{entity.polarity}, #{entity.confidence}, #{entity.status},
                    #{entity.sourceCursor}, #{entity.generationBatchId}::uuid,
                    #{entity.observedAt}, #{entity.expiresAt}, #{entity.promotedFactId}::uuid)
            """)
    int insertObservation(@Param("entity") ContactMemoryObservationEntity entity,
                          @Param("contactId") UUID contactId,
                          @Param("ownerUserId") UUID ownerUserId);

    @Insert("""
            insert into contact_memory_observation_evidence
                (id, observation_id, contact_id, owner_user_id, evidence_type, evidence_id,
                 evidence_excerpt, generation_batch_id)
            values (coalesce(#{entity.id}, gen_random_uuid()), #{entity.observationId}::uuid,
                    #{contactId}::uuid, #{ownerUserId}::uuid, #{entity.evidenceType},
                    #{entity.evidenceId}::uuid, #{entity.evidenceExcerpt},
                    #{entity.generationBatchId}::uuid)
            on conflict (observation_id, evidence_type, evidence_id) do nothing
            """)
    int insertObservationEvidence(@Param("entity") ContactMemoryObservationEvidenceEntity entity,
                                  @Param("contactId") UUID contactId,
                                  @Param("ownerUserId") UUID ownerUserId);

    @Insert("""
            insert into contact_memory_facts
                (id, contact_id, owner_user_id, category, normalized_key, normalized_value,
                 display_value, polarity, status, confidence, evidence_count, first_seen_at,
                 last_seen_at, last_confirmed_at, stale_at, invalidated_at, generation_batch_id)
            values (coalesce(#{entity.id}, gen_random_uuid()), #{contactId}::uuid, #{ownerUserId}::uuid,
                    #{entity.category}, #{entity.normalizedKey}, #{entity.normalizedValue},
                    #{entity.displayValue}, #{entity.polarity}, #{entity.status},
                    #{entity.confidence}, #{entity.evidenceCount}, #{entity.firstSeenAt},
                    #{entity.lastSeenAt}, #{entity.lastConfirmedAt}, #{entity.staleAt},
                    #{entity.invalidatedAt}, #{entity.generationBatchId}::uuid)
            on conflict (contact_id, owner_user_id, category, normalized_key, normalized_value, polarity)
            do nothing
            """)
    int insertFact(@Param("entity") ContactMemoryFactEntity entity,
                   @Param("contactId") UUID contactId,
                   @Param("ownerUserId") UUID ownerUserId);

    @Update("""
            update contact_memory_facts
            set display_value = #{entity.displayValue},
                status = #{entity.status},
                confidence = #{entity.confidence},
                evidence_count = #{entity.evidenceCount},
                last_seen_at = #{entity.lastSeenAt},
                last_confirmed_at = #{entity.lastConfirmedAt},
                stale_at = #{entity.staleAt},
                invalidated_at = #{entity.invalidatedAt},
                generation_batch_id = #{entity.generationBatchId}::uuid
            where id = #{entity.id}::uuid
              and contact_id = #{contactId}::uuid
              and owner_user_id = #{ownerUserId}::uuid
            """)
    int updateFact(@Param("entity") ContactMemoryFactEntity entity,
                   @Param("contactId") UUID contactId,
                   @Param("ownerUserId") UUID ownerUserId);

    @Select("""
            select count(*)
            from contact_memory_fact_evidence
            where fact_id = #{factId}::uuid
              and contact_id = #{contactId}::uuid
              and owner_user_id = #{ownerUserId}::uuid
            """)
    long countFactEvidence(@Param("factId") UUID factId,
                           @Param("contactId") UUID contactId,
                           @Param("ownerUserId") UUID ownerUserId);

    @Select("""
            select *
            from contact_memory_facts
            where contact_id = #{contactId}::uuid
              and owner_user_id = #{ownerUserId}::uuid
              and category = #{category}
              and status = 'ACTIVE'
              and (normalized_key = #{normalizedName} or normalized_value = #{normalizedName})
            order by case when normalized_value = #{normalizedName} then 0 else 1 end, last_confirmed_at desc
            limit 1
            """)
    ContactMemoryFactEntity findActiveFactForLabel(@Param("ownerUserId") UUID ownerUserId,
                                                   @Param("contactId") UUID contactId,
                                                   @Param("category") String category,
                                                   @Param("normalizedName") String normalizedName);

    @Select("""
            select *
            from contact_memory_facts
            where contact_id = #{contactId}::uuid
              and owner_user_id = #{ownerUserId}::uuid
              and category = #{category}
              and normalized_key = #{normalizedKey}
              and normalized_value = #{normalizedValue}
              and polarity <> #{polarity}
              and status = 'ACTIVE'
            limit 1
            """)
    ContactMemoryFactEntity findOppositeActiveFact(@Param("ownerUserId") UUID ownerUserId,
                                                   @Param("contactId") UUID contactId,
                                                   @Param("category") String category,
                                                   @Param("normalizedKey") String normalizedKey,
                                                   @Param("normalizedValue") String normalizedValue,
                                                   @Param("polarity") String polarity);

    @Select("""
            select *
            from contact_memory_facts
            where contact_id = #{contactId}::uuid
              and owner_user_id = #{ownerUserId}::uuid
              and category = #{category}
              and normalized_key = #{normalizedKey}
              and normalized_value = #{normalizedValue}
              and polarity = #{polarity}
            limit 1
            """)
    ContactMemoryFactEntity findFact(@Param("ownerUserId") UUID ownerUserId,
                                     @Param("contactId") UUID contactId,
                                     @Param("category") String category,
                                     @Param("normalizedKey") String normalizedKey,
                                     @Param("normalizedValue") String normalizedValue,
                                     @Param("polarity") String polarity);

    @Insert("""
            insert into contact_memory_fact_evidence
                (id, fact_id, contact_id, owner_user_id, evidence_type, evidence_id,
                 evidence_excerpt, generation_batch_id)
            values (coalesce(#{entity.id}, gen_random_uuid()), #{entity.factId}::uuid,
                    #{contactId}::uuid, #{ownerUserId}::uuid, #{entity.evidenceType},
                    #{entity.evidenceId}::uuid, #{entity.evidenceExcerpt},
                    #{entity.generationBatchId}::uuid)
            on conflict (fact_id, evidence_type, evidence_id) do nothing
            """)
    int insertFactEvidence(@Param("entity") ContactMemoryFactEvidenceEntity entity,
                           @Param("contactId") UUID contactId,
                           @Param("ownerUserId") UUID ownerUserId);

    @Insert("""
            insert into contact_profile_versions
                (id, contact_id, owner_user_id, version, content, source_cursor,
                 generation_batch_id, model, input_message_count, evidence_count, is_current)
            values (coalesce(#{entity.id}, gen_random_uuid()), #{contactId}::uuid,
                    #{ownerUserId}::uuid, #{entity.version}, #{entity.content},
                    #{entity.sourceCursor}, #{entity.generationBatchId}::uuid,
                    #{entity.model}, #{entity.inputMessageCount}, #{entity.evidenceCount},
                    #{entity.isCurrent})
            """)
    int insertProfile(@Param("entity") ContactProfileVersionEntity entity,
                      @Param("contactId") UUID contactId,
                      @Param("ownerUserId") UUID ownerUserId);

    @Update("""
            update contact_profile_versions
            set is_current = false
            where contact_id = #{contactId}::uuid
              and owner_user_id = #{ownerUserId}::uuid
              and is_current
            """)
    int clearCurrentProfile(@Param("contactId") UUID contactId,
                            @Param("ownerUserId") UUID ownerUserId);

    @Select("""
            select coalesce(max(version), 0) + 1
            from contact_profile_versions
            where contact_id = #{contactId}::uuid
              and owner_user_id = #{ownerUserId}::uuid
            """)
    long nextProfileVersion(@Param("contactId") UUID contactId,
                            @Param("ownerUserId") UUID ownerUserId);

    @Insert("""
            insert into contact_ai_labels
                (id, contact_id, owner_user_id, category, normalized_name, display_name,
                 color_token, status, confidence, first_seen_at, last_seen_at,
                 last_evidence_at, generation_batch_id)
            values (coalesce(#{entity.id}, gen_random_uuid()), #{contactId}::uuid,
                    #{ownerUserId}::uuid, #{entity.category}, #{entity.normalizedName},
                    #{entity.displayName}, #{entity.colorToken}, #{entity.status},
                    #{entity.confidence}, #{entity.firstSeenAt}, #{entity.lastSeenAt},
                    #{entity.lastEvidenceAt}, #{entity.generationBatchId}::uuid)
            on conflict (contact_id, owner_user_id, category, normalized_name)
            do nothing
            """)
    int insertAiLabel(@Param("entity") ContactAiLabelEntity entity,
                      @Param("contactId") UUID contactId,
                      @Param("ownerUserId") UUID ownerUserId);

    @Select("""
            select *
            from contact_ai_labels
            where contact_id = #{contactId}::uuid
              and owner_user_id = #{ownerUserId}::uuid
              and category = #{category}
              and normalized_name = #{normalizedName}
            limit 1
            """)
    ContactAiLabelEntity findAiLabel(@Param("ownerUserId") UUID ownerUserId,
                                     @Param("contactId") UUID contactId,
                                     @Param("category") String category,
                                     @Param("normalizedName") String normalizedName);

    @Update("""
            update contact_ai_labels
            set display_name = #{entity.displayName},
                color_token = #{entity.colorToken},
                status = #{entity.status},
                confidence = #{entity.confidence},
                last_seen_at = #{entity.lastSeenAt},
                last_evidence_at = #{entity.lastEvidenceAt},
                generation_batch_id = #{entity.generationBatchId}::uuid
            where id = #{entity.id}::uuid
              and contact_id = #{contactId}::uuid
              and owner_user_id = #{ownerUserId}::uuid
            """)
    int updateAiLabel(@Param("entity") ContactAiLabelEntity entity,
                      @Param("contactId") UUID contactId,
                      @Param("ownerUserId") UUID ownerUserId);

    @Insert("""
            insert into contact_ai_label_evidence
                (id, label_id, fact_id, contact_id, owner_user_id, evidence_type,
                 evidence_id, evidence_excerpt, generation_batch_id)
            values (coalesce(#{entity.id}, gen_random_uuid()), #{entity.labelId}::uuid,
                    #{entity.factId}::uuid, #{contactId}::uuid, #{ownerUserId}::uuid,
                    #{entity.evidenceType}, #{entity.evidenceId}::uuid,
                    #{entity.evidenceExcerpt}, #{entity.generationBatchId}::uuid)
            on conflict (label_id, fact_id, evidence_type, evidence_id) do nothing
            """)
    int insertAiLabelEvidence(@Param("entity") ContactAiLabelEvidenceEntity entity,
                              @Param("contactId") UUID contactId,
                              @Param("ownerUserId") UUID ownerUserId);

    @Insert("""
            insert into contact_memory_attempts
                (id, contact_id, owner_user_id, generation_batch_id, input_cursor,
                 output_cursor, status, failure_code, failure_message, model, duration_ms,
                 input_message_count, output_label_change_count, profile_changed,
                 retry_count, completed_at)
            values (coalesce(#{entity.id}, gen_random_uuid()), #{contactId}::uuid,
                    #{ownerUserId}::uuid, #{entity.generationBatchId}::uuid,
                    #{entity.inputCursor}, #{entity.outputCursor}, #{entity.status},
                    #{entity.failureCode}, #{entity.failureMessage}, #{entity.model},
                    #{entity.durationMs}, #{entity.inputMessageCount},
                    #{entity.outputLabelChangeCount}, #{entity.profileChanged},
                    #{entity.retryCount}, #{entity.completedAt})
            """)
    int insertAttempt(@Param("entity") ContactMemoryAttemptEntity entity,
                      @Param("contactId") UUID contactId,
                      @Param("ownerUserId") UUID ownerUserId);
}
