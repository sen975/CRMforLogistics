package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.OutboxJobEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface OutboxJobMapper extends BaseMapper<OutboxJobEntity> {
    @Insert("insert into outbox_jobs (id, message_id, job_type, status, attempt_count, max_attempts, next_attempt_at) " +
            "values (#{id}::uuid, #{messageId}::uuid, #{jobType}, #{status}, #{attemptCount}, #{maxAttempts}, #{nextAttemptAt}) " +
            "on conflict (message_id) do nothing")
    int insertIgnore(OutboxJobEntity job);

    @Select("with picked as (" +
            "select id from outbox_jobs " +
            "where status in ('pending', 'retry_wait') and next_attempt_at <= now() " +
            "and attempt_count < max_attempts " +
            "and (lease_until is null or lease_until < now()) " +
            "order by next_attempt_at, created_at for update skip locked limit #{batchSize}" +
            ") update outbox_jobs j set status = 'processing', lease_owner = #{workerId}, " +
            "lease_until = #{leaseUntil}, updated_at = now() from picked " +
            "where j.id = picked.id returning j.*")
    List<OutboxJobEntity> claimDue(@Param("workerId") String workerId,
                                   @Param("leaseUntil") Instant leaseUntil,
                                   @Param("batchSize") int batchSize);

    @Update("update outbox_jobs set status = 'completed', attempt_count = attempt_count + 1, "
            + "completed_at = #{completedAt}, "
            + "updated_at = #{updatedAt}, lease_owner = null, lease_until = null "
            + "where id = #{id}::uuid and status = 'processing' and lease_owner = #{leaseOwner}")
    int completeIfOwned(@Param("id") UUID id, @Param("leaseOwner") String leaseOwner,
                        @Param("completedAt") Instant completedAt,
                        @Param("updatedAt") Instant updatedAt);

    @Update("update outbox_jobs set status = 'dead', attempt_count = attempt_count + 1, "
            + "last_error_code = #{code}, "
            + "last_error_message = #{message}, updated_at = #{updatedAt}, "
            + "lease_owner = null, lease_until = null "
            + "where id = #{id}::uuid and status = 'processing' and lease_owner = #{leaseOwner}")
    int markDeadIfOwned(@Param("id") UUID id, @Param("leaseOwner") String leaseOwner,
                        @Param("code") String code, @Param("message") String message,
                        @Param("updatedAt") Instant updatedAt);

    @Update("update outbox_jobs set status = 'retry_wait', attempt_count = attempt_count + 1, "
            + "next_attempt_at = #{nextAttemptAt}, last_error_code = #{code}, "
            + "last_error_message = #{message}, updated_at = #{updatedAt}, "
            + "lease_owner = null, lease_until = null "
            + "where id = #{id}::uuid and status = 'processing' and lease_owner = #{leaseOwner}")
    int retryIfOwned(@Param("id") UUID id, @Param("leaseOwner") String leaseOwner,
                     @Param("nextAttemptAt") Instant nextAttemptAt,
                     @Param("code") String code, @Param("message") String message,
                     @Param("updatedAt") Instant updatedAt);

    @Select("with expired as (" +
            "select id, message_id from outbox_jobs " +
            "where status = 'processing' and lease_until < #{now}" +
            ") update outbox_jobs j set status = 'dead', " +
            "attempt_count = least(attempt_count + 1, max_attempts), " +
            "last_error_code = 'SUBMISSION_UNKNOWN', " +
            "last_error_message = 'Lease expired after provider submission; reconcile by task id', " +
            "updated_at = #{now}, lease_owner = null, lease_until = null " +
            "from expired where j.id = expired.id returning j.message_id")
    List<UUID> recoverExpiredProcessing(@Param("now") Instant now);
}
