package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.ContactMemoryStateEntity;
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
                "listInboundMessages",
                java.util.UUID.class,
                java.util.UUID.class,
                java.time.Instant.class,
                java.time.Instant.class,
                int.class);
        String sql = normalizedSql(method.getAnnotation(Select.class).value());

        assertThat(sql).contains("c.created_by = #{ownerUserId}::uuid")
                .contains("m.direction = 'inbound'")
                .contains("m.occurred_at > #{after}")
                .contains("m.occurred_at <= #{cutoff}")
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
                "complete", java.util.UUID.class, String.class, String.class, java.time.Instant.class);

        String claimSql = normalizedSql(claim.getAnnotation(Update.class).value());
        String completeSql = normalizedSql(complete.getAnnotation(Update.class).value());

        assertThat(claimSql).contains("lease_owner = #{leaseOwner}")
                .contains("lease_expires_at = #{leaseUntil}")
                .contains("status in ('DIRTY', 'RETRY_WAIT')")
                .contains("lease_expires_at < now()");
        assertThat(completeSql).contains("lease_owner = #{leaseOwner}")
                .contains("last_success_cursor = #{cursor}")
                .contains("status = 'PROCESSING'");
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

    private String normalizedSql(String[] fragments) {
        return String.join(" ", fragments)
                .replace("&gt;", ">")
                .replace("&lt;", "<");
    }
}
