package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastJobEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface ChatAppBroadcastJobMapper extends BaseMapper<ChatAppBroadcastJobEntity> {

    @Insert("insert into chatapp_broadcast_jobs (id, broadcast_id, job_type, status, "
            + "attempt_count, max_attempts, next_attempt_at, created_at, updated_at) "
            + "select #{job.id}::uuid, #{job.broadcastId}::uuid, 'RECONCILE', 'PENDING', "
            + "0, 10, #{job.nextAttemptAt}, #{job.createdAt}, #{job.updatedAt} "
            + "where not exists (select 1 from chatapp_broadcast_jobs "
            + "where broadcast_id = #{job.broadcastId}::uuid and job_type = 'RECONCILE' "
            + "and status in ('PENDING','PROCESSING','FAILED')) on conflict do nothing")
    int insertReconcileIfAbsent(@Param("job") ChatAppBroadcastJobEntity job);

    @Select("with picked as (select id from chatapp_broadcast_jobs "
            + "where ((status in ('PENDING', 'FAILED') and next_attempt_at <= #{now} "
            + "and attempt_count < max_attempts and lease_expires_at is null) "
            + "or (status = 'PROCESSING' and job_type = 'RECONCILE' "
            + "and lease_expires_at < #{now})) "
            + "order by next_attempt_at, created_at for update skip locked limit #{limit}) "
            + "update chatapp_broadcast_jobs job set status = 'PROCESSING', "
            + "lease_id = gen_random_uuid()::text, lease_worker_id = #{workerId}, "
            + "lease_expires_at = #{leaseExpiresAt}, updated_at = #{now} from picked "
            + "where job.id = picked.id returning job.*")
    List<ChatAppBroadcastJobEntity> claimDue(@Param("workerId") String workerId,
                                             @Param("now") Instant now,
                                             @Param("leaseExpiresAt") Instant leaseExpiresAt,
                                             @Param("limit") int limit);

    @Update("update chatapp_broadcast_jobs set status = 'SUCCEEDED', attempt_count = attempt_count + 1, "
            + "lease_id = null, lease_worker_id = null, lease_expires_at = null, "
            + "last_error_code = null, last_error_message = null, updated_at = #{now} "
            + "where id = #{id}::uuid and status = 'PROCESSING' and lease_id = #{leaseId}")
    int completeIfLeased(@Param("id") UUID id,
                         @Param("leaseId") String leaseId,
                         @Param("now") Instant now);

    @Update("update chatapp_broadcast_jobs set status = #{status}, attempt_count = attempt_count + 1, "
            + "next_attempt_at = #{nextAttemptAt}, lease_id = null, lease_worker_id = null, "
            + "lease_expires_at = null, last_error_code = #{errorCode}, "
            + "last_error_message = #{errorMessage}, updated_at = #{now} "
            + "where id = #{id}::uuid and status = 'PROCESSING' and lease_id = #{leaseId}")
    int failIfLeased(@Param("id") UUID id,
                     @Param("leaseId") String leaseId,
                     @Param("status") String status,
                     @Param("nextAttemptAt") Instant nextAttemptAt,
                     @Param("errorCode") String errorCode,
                     @Param("errorMessage") String errorMessage,
                     @Param("now") Instant now);

    @Update("update chatapp_broadcast_jobs set updated_at = updated_at "
            + "where id = #{id}::uuid and status = 'PROCESSING' and lease_id = #{leaseId}")
    int assertLeaseOwned(@Param("id") UUID id, @Param("leaseId") String leaseId);

    @Select("with expired as (select id, broadcast_id from chatapp_broadcast_jobs "
            + "where job_type = 'SUBMIT' and status = 'PROCESSING' "
            + "and lease_expires_at < #{now} order by lease_expires_at "
            + "for update skip locked limit 100) "
            + "update chatapp_broadcast_jobs job set status = 'DEAD', "
            + "attempt_count = least(attempt_count + 1, max_attempts), "
            + "lease_id = null, lease_worker_id = null, lease_expires_at = null, "
            + "last_error_code = 'CHATAPP_BROADCAST_SUBMISSION_UNKNOWN', "
            + "last_error_message = 'CHATAPP_BROADCAST_SUBMISSION_UNKNOWN', "
            + "updated_at = #{now} from expired where job.id = expired.id "
            + "returning expired.broadcast_id")
    List<UUID> recoverExpiredSubmissions(@Param("now") Instant now);
}
