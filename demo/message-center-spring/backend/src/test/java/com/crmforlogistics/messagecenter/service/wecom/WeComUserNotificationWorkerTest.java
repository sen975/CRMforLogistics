package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComSendService;
import com.crmforlogistics.messagecenter.entity.WeComUserNotificationEntity;
import com.crmforlogistics.messagecenter.mapper.WeComUserNotificationMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComUserNotificationWorkerTest {

    private static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");
    private static final UUID ROW = UUID.fromString("40000000-0000-0000-0000-000000000001");

    private final WeComUserNotificationMapper notifications = mock(WeComUserNotificationMapper.class);
    private final WeComSendService sendService = mock(WeComSendService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void sendsAggregatedTextThroughTheExistingWeComSendService() {
        WeComUserNotificationEntity row = row(3, "你好，想问下运费");
        when(notifications.listDue(NOW, 10)).thenReturn(List.of(row));
        when(notifications.claim(ROW)).thenReturn(1);
        when(sendService.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new WeComSendService.SendResult("msg-1", "1000002", "zhangsan", "x", "sent"));
        WeComUserNotificationWorker worker = worker();

        worker.runAvailable("worker-1", 10);

        verify(sendService).send("corp-1", "1000002", "zhangsan",
                "【WhatsApp】张三 给你发了 3 条消息，最近一条：你好，想问下运费");
        verify(notifications).markSent(ROW, NOW);
    }

    @Test
    void skipsARowAlreadyClaimedByAnotherWorker() {
        when(notifications.listDue(NOW, 10)).thenReturn(List.of(row(1, "hi")));
        when(notifications.claim(ROW)).thenReturn(0);
        WeComUserNotificationWorker worker = worker();

        worker.runAvailable("worker-1", 10);

        verify(sendService, never()).send(anyString(), anyString(), anyString(), anyString());
        verify(notifications, never()).markSent(any(), any());
    }

    @Test
    void retriesUntilTheAttemptBudgetIsSpent() {
        when(notifications.listDue(NOW, 10)).thenReturn(List.of(row(1, "hi")));
        when(notifications.claim(ROW)).thenReturn(1);
        when(sendService.send(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new WeComException("WECOM_SEND_FAILED", 502, "企业微信消息发送失败"));
        WeComUserNotificationWorker worker = worker();

        worker.runAvailable("worker-1", 10);

        verify(notifications).retryLater(eq(ROW), eq(NOW.plus(Duration.ofSeconds(10))),
                anyString());
        verify(notifications, never()).markFailed(any(), any());
    }

    @Test
    void failsTheRowOnceTheAttemptBudgetIsExhausted() {
        WeComUserNotificationEntity row = row(1, "hi");
        row.setAttemptCount(2);
        when(notifications.listDue(NOW, 10)).thenReturn(List.of(row));
        when(notifications.claim(ROW)).thenReturn(1);
        when(sendService.send(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new WeComException("WECOM_SEND_FAILED", 502, "企业微信消息发送失败"));
        WeComUserNotificationWorker worker = worker();

        worker.runAvailable("worker-1", 10);

        verify(notifications).markFailed(ROW, "WECOM_SEND_FAILED");
        verify(notifications, never()).retryLater(any(), any(), anyString());
    }

    @Test
    void aFailureOnOneRowDoesNotStopTheRestOfTheBatch() {
        WeComUserNotificationEntity failing = row(1, "boom");
        WeComUserNotificationEntity ok = row(1, "fine");
        UUID okId = UUID.fromString("40000000-0000-0000-0000-000000000002");
        ok.setId(okId);
        when(notifications.listDue(NOW, 10)).thenReturn(List.of(failing, ok));
        when(notifications.claim(ROW)).thenReturn(1);
        when(notifications.claim(okId)).thenReturn(1);
        when(sendService.send(anyString(), anyString(), anyString(), eq("【WhatsApp】张三：boom")))
                .thenThrow(new WeComException("WECOM_SEND_FAILED", 502, "企业微信消息发送失败"));
        when(sendService.send(anyString(), anyString(), anyString(), eq("【WhatsApp】张三：fine")))
                .thenReturn(new WeComSendService.SendResult("msg-2", "1000002", "zhangsan", "x", "sent"));
        WeComUserNotificationWorker worker = worker();

        worker.runAvailable("worker-1", 10);

        verify(notifications).retryLater(eq(ROW), any(), anyString());
        verify(notifications).markSent(okId, NOW);
    }

    @Test
    void recoversRowsLeakedInSending() {
        when(notifications.recoverStuck(NOW.minus(Duration.ofSeconds(60))))
                .thenReturn(List.of(ROW));
        WeComUserNotificationWorker worker = worker();

        assertThat(worker.recoverStuck()).isEqualTo(1);
    }

    private WeComUserNotificationWorker worker() {
        // maxAttempts=3, retryBackoffSeconds=10
        return new WeComUserNotificationWorker(notifications, sendService, clock, 3, 10L);
    }

    private static WeComUserNotificationEntity row(int messageCount, String preview) {
        WeComUserNotificationEntity row = new WeComUserNotificationEntity();
        row.setId(ROW);
        row.setChannelType("chatapp");
        row.setContactLabel("张三");
        row.setMessageCount(messageCount);
        row.setLastPreview(preview);
        row.setAuthCorpId("corp-1");
        row.setAgentId("1000002");
        row.setRecipientWecomUserId("zhangsan");
        row.setAttemptCount(0);
        return row;
    }
}
