package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ContactMemoryStateMapper} 里那几条并发 / 脏标记 SQL 的<b>形状</b>。
 *
 * <p>这些 SQL 一写错，症状都不是一条失败的断言，而是线上的静默错误：
 * 租约会退化成「谁都能重复领」、脏标记会退化成「处理完立刻又变脏」。
 * 所以下面断的是结构（值从哪来、判据是什么、允许从哪些状态进入），
 * 而不是「SQL 里有这几个词」。
 *
 * <p>真库上的行为（真的只影响 0 行 / 1 行、真的保留原值）由
 * {@code ContactMemoryEndToEndTest} 验证 —— 这个类只保证「SQL 长对了」，
 * 不假装跑过数据库。
 */
class ContactMemoryStateMapperSqlTest {

    @Test
    void claimReturnsAnIndependentFencingToken() throws Exception {
        var method = ContactMemoryStateMapper.class.getMethod(
                "claim", UUID.class, String.class, Instant.class);
        String sql = normalize(method.getAnnotation(Select.class).value());

        assertThat(sql).contains("lease_token = gen_random_uuid()")
                .contains("returning lease_token")
                .contains("lease_expires_at = #{leaseUntil}")
                .contains("status = 'PROCESSING'");
    }

    @Test
    void completionAndFailureRequireTheCurrentUnexpiredToken() throws Exception {
        var complete = ContactMemoryStateMapper.class.getMethod(
                "complete", UUID.class, UUID.class, String.class, UUID.class, Instant.class);
        var fail = ContactMemoryStateMapper.class.getMethod(
                "fail", UUID.class, UUID.class, String.class, String.class,
                int.class, Instant.class, boolean.class);

        String completeSql = normalize(complete.getAnnotation(Update.class).value());
        String failSql = normalize(fail.getAnnotation(Update.class).value());

        assertThat(completeSql).contains("lease_token = #{leaseToken}::uuid")
                .contains("status = 'PROCESSING'")
                .contains("lease_expires_at > now()")
                .contains("last_success_cursor = #{cursor}");
        assertThat(failSql).contains("lease_token = #{leaseToken}::uuid")
                .contains("status = 'PROCESSING'")
                .contains("lease_expires_at > now()");
    }

    // ---------- 手动重算的标记路径（2026-09-23） ----------

    @Test
    void theManualPathTakesItsInboundTimeFromTheMessageTableNeverFromTheCaller() throws Exception {
        String sql = normalize(markDirtyForRecompute());

        assertThat(sql)
                .contains("max(m.received_at) as last_inbound_at")
                .contains("m.direction = 'inbound'")
                .contains("c.created_by = #{ownerUserId}::uuid")
                .contains("latest.last_inbound_at, 0, now()");
        assertThat(sql)
                .as("写进 last_inbound_at 的只能是消息表里的真实时间。传 now 会造出「处理完又立刻变脏」的死循环")
                .doesNotContain("#{inboundAt}");

        // 对照组：会把「调用方给的时间」写进去的是另一个方法。它存在本身说明这个差别是刻意的，
        // 也防住「顺手把新方法简化成调用 markDirty」这种看起来无害的重构。
        var blind = ContactMemoryStateMapper.class.getMethod(
                "markDirty", UUID.class, UUID.class, Instant.class);
        assertThat(normalize(blind.getAnnotation(Insert.class).value()))
                .as("对照组：这个方法确实把参数当 last_inbound_at 写进去（所以手动路径不能走它）")
                .contains("#{inboundAt}");
    }

    @Test
    void theManualPathSharesTheSameJudgeAsTheAutomaticPath() throws Exception {
        String manual = normalize(markDirtyForRecompute());
        String automatic = normalize(ContactMemoryStateMapper.class
                .getMethod("markStaleDirty", Instant.class, int.class)
                .getAnnotation(Insert.class).value());

        String judge = "split_part(st.last_success_cursor, '|', 1)::timestamptz";
        assertThat(manual).as("手动路径的判据").contains(judge);
        assertThat(automatic).as("自动路径的判据（两处必须同源，改一处等于改两处）").contains(judge);
    }

    @Test
    void onlyCleanOrFailedStatesMayBeMarkedDirtyByHand() throws Exception {
        assertThat(normalize(markDirtyForRecompute()))
                .contains("on conflict (contact_id, owner_user_id) do update")
                .contains("where contact_memory_states.status in ('CLEAN', 'FAILED')")
                .as("DIRTY / RETRY_WAIT / PROCESSING 不许被手动改脏：那会把正在跑的那一轮的中间状态搅掉")
                .doesNotContain("'PROCESSING'");
    }

    private static String[] markDirtyForRecompute() throws Exception {
        return ContactMemoryStateMapper.class
                .getMethod("markDirtyForRecompute", UUID.class, UUID.class, Instant.class)
                .getAnnotation(Insert.class).value();
    }

    private static String normalize(String[] fragments) {
        return String.join(" ", fragments).replace("&gt;", ">")
                .replace("&lt;", "<");
    }
}
