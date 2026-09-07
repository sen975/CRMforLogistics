package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.WeComExternalGroupSyncEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface WeComExternalGroupSyncMapper extends BaseMapper<WeComExternalGroupSyncEntity> {
    @Insert("insert into wecom_external_group_syncs (id, installation_id, status, cursor, page_count, "
            + "attempt_count, next_attempt_at, created_at, updated_at) "
            + "select gen_random_uuid(), wi.id, 'PENDING', '', 0, 0, #{now}, #{now}, #{now} "
            + "from wecom_installations wi where wi.auth_status='ACTIVE' and wi.deleted_at is null "
            + "and not exists (select 1 from wecom_external_group_syncs active where "
            + "active.installation_id=wi.id and active.status in ('PENDING','PROCESSING','RETRY_WAIT')) "
            + "and (not exists (select 1 from wecom_external_group_syncs recent where "
            + "recent.installation_id=wi.id and coalesce(recent.completed_at, recent.created_at) > #{cutoff}) "
            + "or exists (select 1 from wecom_source_conversations unknown where "
            + "unknown.installation_id=wi.id and unknown.conversation_type='GROUP' "
            + "and unknown.group_kind='UNKNOWN' and not exists (select 1 from "
            + "wecom_external_group_syncs attempted where attempted.installation_id=wi.id "
            + "and attempted.created_at >= unknown.first_seen_at))) on conflict do nothing")
    int enqueueDue(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    @Select("select * from wecom_external_group_syncs where "
            + "((status in ('PENDING','RETRY_WAIT') and next_attempt_at <= #{now}) "
            + "or (status='PROCESSING' and lease_until < #{now})) "
            + "order by next_attempt_at, created_at, id limit #{limit}")
    List<WeComExternalGroupSyncEntity> listRunnable(@Param("now") Instant now, @Param("limit") int limit);

    @Update("update wecom_external_group_syncs set status='PROCESSING', attempt_count=attempt_count+1, "
            + "lease_owner=#{owner}, lease_until=#{leaseUntil}, updated_at=now() where id=#{id}::uuid "
            + "and (status in ('PENDING','RETRY_WAIT') or (status='PROCESSING' and lease_until < now()))")
    int claim(@Param("id") UUID id, @Param("owner") String owner, @Param("leaseUntil") Instant leaseUntil);

    @Insert({"<script>",
            "insert into wecom_external_group_sync_items (sync_id, chat_id) values ",
            "<foreach collection='chatIds' item='chatId' separator=','>(#{syncId}::uuid, #{chatId})</foreach>",
            " on conflict do nothing",
            "</script>"})
    int insertItems(@Param("syncId") UUID syncId, @Param("chatIds") List<String> chatIds);

    @Update("update wecom_external_group_syncs set status='PENDING', cursor=#{cursor}, "
            + "page_count=page_count+1, attempt_count=0, next_attempt_at=#{now}, lease_owner=null, "
            + "lease_until=null, error_code=null, updated_at=#{now} where id=#{id}::uuid "
            + "and status='PROCESSING' and lease_owner=#{owner}")
    int advance(@Param("id") UUID id, @Param("owner") String owner,
                @Param("cursor") String cursor, @Param("now") Instant now);

    @Update("update wecom_source_conversations sc set group_kind='EXTERNAL', updated_at=now() "
            + "from wecom_external_group_sync_items item where item.sync_id=#{syncId}::uuid "
            + "and sc.installation_id=#{installationId}::uuid and sc.conversation_type='GROUP' "
            + "and sc.provider_conversation_key='group:' || item.chat_id and sc.group_kind <> 'EXTERNAL'")
    int markSeenExternal(@Param("installationId") UUID installationId, @Param("syncId") UUID syncId);

    @Update("update wecom_source_conversations sc set group_kind='INTERNAL', display_name=null, updated_at=now() "
            + "from wecom_external_group_syncs sync where sync.id=#{syncId}::uuid "
            + "and sync.installation_id=#{installationId}::uuid and sc.installation_id=sync.installation_id "
            + "and sc.conversation_type='GROUP' and sc.group_kind='UNKNOWN' "
            + "and sc.first_seen_at <= sync.created_at and not exists (select 1 from "
            + "wecom_external_group_sync_items item where item.sync_id=sync.id "
            + "and sc.provider_conversation_key='group:' || item.chat_id)")
    int markUnknownUnseenInternal(@Param("installationId") UUID installationId,
                                  @Param("syncId") UUID syncId);

    @Update("update wecom_external_group_syncs set status='COMPLETED', page_count=page_count+1, "
            + "attempt_count=0, completed_at=#{now}, next_attempt_at=#{now}, lease_owner=null, lease_until=null, "
            + "error_code=null, updated_at=#{now} where id=#{id}::uuid and status='PROCESSING' "
            + "and lease_owner=#{owner}")
    int complete(@Param("id") UUID id, @Param("owner") String owner, @Param("now") Instant now);

    @Update("update wecom_external_group_syncs set status='RETRY_WAIT', next_attempt_at=#{nextAttemptAt}, "
            + "lease_owner=null, lease_until=null, error_code=#{errorCode}, updated_at=now() "
            + "where id=#{id}::uuid and status='PROCESSING' and lease_owner=#{owner}")
    int retry(@Param("id") UUID id, @Param("owner") String owner,
              @Param("errorCode") String errorCode, @Param("nextAttemptAt") Instant nextAttemptAt);

    @Update("update wecom_external_group_syncs set status='FAILED', completed_at=#{now}, next_attempt_at=#{now}, "
            + "lease_owner=null, lease_until=null, error_code=#{errorCode}, updated_at=#{now} "
            + "where id=#{id}::uuid and status='PROCESSING' and lease_owner=#{owner}")
    int fail(@Param("id") UUID id, @Param("owner") String owner,
             @Param("errorCode") String errorCode, @Param("now") Instant now);

    @Delete("delete from wecom_external_group_sync_items where sync_id=#{syncId}::uuid")
    int deleteItems(@Param("syncId") UUID syncId);
}
