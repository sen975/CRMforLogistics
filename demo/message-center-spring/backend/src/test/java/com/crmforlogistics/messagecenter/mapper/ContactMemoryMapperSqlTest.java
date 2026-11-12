package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.ContactMemoryStateEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ContactMemoryMapperSqlTest {

    @Test
    void inboundMessageQueryUsesOwnerDirectionCutoffAndLimit() throws Exception {
        Method method = ContactMemoryMapper.class.getMethod(
                "listInboundMessagesByCursor",
                java.util.UUID.class,
                java.util.UUID.class,
                java.time.Instant.class,
                java.util.UUID.class,
                java.time.Instant.class,
                int.class);
        String sql = normalizedSql(method.getAnnotation(Select.class).value());

        assertThat(sql).contains("c.created_by = #{ownerUserId}::uuid")
                .contains("m.direction = 'inbound'")
                .contains("m.received_at > #{afterReceivedAt}")
                .contains("m.received_at = #{afterReceivedAt}")
                .contains("m.id > #{afterMessageId}::uuid")
                .contains("m.received_at <= #{cutoff}")
                .doesNotContain("m.occurred_at <= #{cutoff}")
                .contains("limit #{limit}");
    }

    @Test
    void stateRunnableQueryScopesStatusesAndUsesDueLeaseOrdering() throws Exception {
        Method method = ContactMemoryStateMapper.class.getMethod(
                "listRunnable", java.time.Instant.class, java.time.Instant.class, int.class);
        String sql = normalizedSql(method.getAnnotation(Select.class).value());

        assertThat(sql).contains("status = 'DIRTY'")
                .contains("status = 'RETRY_WAIT'")
                .contains("status = 'PROCESSING'")
                .contains("lease_expires_at < #{now}")
                .contains("limit #{limit}");
    }

    @Test
    void stateClaimAndCompletionAreLeaseGuarded() throws Exception {
        Method claim = ContactMemoryStateMapper.class.getMethod(
                "claim", java.util.UUID.class, String.class, java.time.Instant.class);
        Method complete = ContactMemoryStateMapper.class.getMethod(
                "complete", java.util.UUID.class, java.util.UUID.class, String.class,
                java.util.UUID.class, java.time.Instant.class);
        Method fail = ContactMemoryStateMapper.class.getMethod(
                "fail", java.util.UUID.class, java.util.UUID.class, String.class, String.class,
                int.class, java.time.Instant.class, boolean.class);

        String claimSql = normalizedSql(claim.getAnnotation(Select.class).value());
        String completeSql = normalizedSql(complete.getAnnotation(Update.class).value());
        String failSql = normalizedSql(fail.getAnnotation(Update.class).value());

        assertThat(claimSql).contains("lease_owner = #{leaseOwner}")
                .contains("lease_token = gen_random_uuid()")
                .contains("lease_expires_at = #{leaseUntil}")
                .contains("status in ('DIRTY', 'RETRY_WAIT')")
                .contains("returning lease_token");
        assertThat(completeSql).contains("lease_token = #{leaseToken}::uuid")
                .contains("last_success_cursor = #{cursor}")
                .contains("current_profile_version_id = coalesce(#{profileId}::uuid, current_profile_version_id)")
                .contains("status = 'PROCESSING'")
                .contains("lease_expires_at > now()");
        assertThat(failSql).contains("lease_token = #{leaseToken}::uuid")
                .contains("status = 'PROCESSING'")
                .contains("lease_expires_at > now()");
    }

    @Test
    void memoryEntitiesDeclareTheirExactTables() {
        assertThat(ContactMemoryStateEntity.class.getAnnotation(com.baomidou.mybatisplus.annotation.TableName.class).value())
                .isEqualTo("contact_memory_states");
        assertThat(Arrays.stream(new Class<?>[]{
                com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEntity.class,
                com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEvidenceEntity.class,
                com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity.class,
                com.crmforlogistics.messagecenter.entity.ContactMemoryFactEvidenceEntity.class,
                com.crmforlogistics.messagecenter.entity.ContactProfileVersionEntity.class,
                com.crmforlogistics.messagecenter.entity.ContactAiLabelEntity.class,
                com.crmforlogistics.messagecenter.entity.ContactAiLabelEvidenceEntity.class,
                com.crmforlogistics.messagecenter.entity.ContactMemoryAttemptEntity.class
                }).allMatch(type -> type.isAnnotationPresent(com.baomidou.mybatisplus.annotation.TableName.class)))
                .isTrue();
    }

    @Test
    void observationEvidenceProjectionIsNotPersistedAsColumns() throws Exception {
        TableField evidence = ContactMemoryObservationEntity.class
                .getDeclaredField("evidence").getAnnotation(TableField.class);
        TableField evidenceCount = ContactMemoryObservationEntity.class
                .getDeclaredField("evidenceCount").getAnnotation(TableField.class);

        assertThat(evidence).isNotNull();
        assertThat(evidence.exist()).isFalse();
        assertThat(evidenceCount).isNotNull();
        assertThat(evidenceCount.exist()).isFalse();
    }

    @Test
    void observationUpsertMergesDuplicateCandidatesAndEvidence() throws Exception {
        Method candidate = ContactMemoryMapper.class.getMethod(
                "findCandidateObservation", java.util.UUID.class, java.util.UUID.class,
                String.class, String.class, String.class, String.class);
        Method method = ContactMemoryMapper.class.getMethod(
                "upsertObservation", ContactMemoryObservationEntity.class,
                java.util.UUID.class, java.util.UUID.class);
        String candidateSql = normalizedSql(candidate.getAnnotation(Select.class).value());
        String sql = normalizedSql(method.getAnnotation(Select.class).value());

        assertThat(candidateSql).contains("lower(trim(o.observed_value)) = lower(trim(#{observedValue}))");
        assertThat(sql).contains("status = 'MERGED'")
                .contains("lower(trim(o.observed_value)) = lower(trim(#{entity.observedValue}))")
                .contains("contact_memory_observation_evidence")
                .contains("on conflict (observation_id, evidence_type, evidence_id) do nothing");
    }

    private String normalizedSql(String[] fragments) {
        return String.join(" ", fragments)
                .replace("&gt;", ">")
                .replace("&lt;", "<");
    }
}
