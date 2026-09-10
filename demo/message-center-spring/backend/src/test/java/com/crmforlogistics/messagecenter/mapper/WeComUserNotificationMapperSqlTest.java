package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.WeComUserNotificationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WeComUserNotificationMapperSqlTest {

    @Test
    void upsertMergesIntoThePendingRowWithoutExtendingTheWindow() throws Exception {
        Method method = WeComUserNotificationMapper.class.getMethod(
                "upsertPending", WeComUserNotificationEntity.class);

        String sql = method.getAnnotation(Insert.class).value()[0];

        assertThat(sql).contains(
                "on conflict (conversation_id, recipient_user_id) where status = 'PENDING'");
        assertThat(sql).contains(
                "message_count = wecom_user_notifications.message_count + 1");
        assertThat(sql).doesNotContain("send_after = excluded.send_after");
        assertThat(sql).doesNotContain("first_message_at = excluded.first_message_at");
        assertThat(sql).doesNotContain("recipient_wecom_user_id = excluded.recipient_wecom_user_id");
    }

    @Test
    void dueScanOnlyLooksAtPendingRowsWhoseSendAfterHasPassed() throws Exception {
        Method method = WeComUserNotificationMapper.class.getMethod(
                "listDue", Instant.class, int.class);

        String sql = method.getAnnotation(Select.class).value()[0];

        assertThat(sql).contains("status = 'PENDING' and send_after <= #{now}");
        assertThat(sql).contains("order by send_after");
    }

    @Test
    void claimOnlyTransitionsFromPending() throws Exception {
        Method method = WeComUserNotificationMapper.class.getMethod("claim", UUID.class);

        String sql = method.getAnnotation(Update.class).value()[0];

        assertThat(sql).contains("status = 'SENDING'");
        assertThat(sql).contains("and status = 'PENDING'");
    }

    @Test
    void terminalTransitionsOnlyApplyToClaimedRows() throws Exception {
        Method sent = WeComUserNotificationMapper.class.getMethod(
                "markSent", UUID.class, Instant.class);
        assertThat(sent.getAnnotation(Update.class).value()[0])
                .contains("status = 'SENT'")
                .contains("and status = 'SENDING'");

        Method failed = WeComUserNotificationMapper.class.getMethod(
                "markFailed", UUID.class, String.class);
        assertThat(failed.getAnnotation(Update.class).value()[0])
                .contains("status = 'FAILED'")
                .contains("and status = 'SENDING'");

        Method retry = WeComUserNotificationMapper.class.getMethod(
                "retryLater", UUID.class, Instant.class, String.class);
        String retrySql = retry.getAnnotation(Update.class).value()[0];
        assertThat(retrySql).contains("status = 'PENDING'");
        assertThat(retrySql).contains("send_after = #{sendAfter}");
        assertThat(retrySql).contains("and status = 'SENDING'");
    }

    @Test
    void recoverStuckReturnsLeakedSendingRowsToPending() throws Exception {
        Method method = WeComUserNotificationMapper.class.getMethod(
                "recoverStuck", Instant.class);

        String sql = method.getAnnotation(Select.class).value()[0];

        assertThat(sql).contains("status = 'SENDING' and updated_at < #{staleBefore}");
        assertThat(sql).contains("for update skip locked");
        assertThat(sql).contains("set status = 'PENDING'");
    }
}
