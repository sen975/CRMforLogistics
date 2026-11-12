package com.crmforlogistics.messagecenter.service.chatapp.broadcast;

import com.crmforlogistics.messagecenter.service.scheduling.AdaptivePollingScheduler;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class ChatAppBroadcastSchedulerTest {

    @Test
    void recoversUnknownSubmissionsBeforeClaimingBoundedWork() {
        ChatAppBroadcastWorker worker = mock(ChatAppBroadcastWorker.class);
        AdaptivePollingScheduler scheduler = mock(AdaptivePollingScheduler.class);
        ChatAppBroadcastScheduler task = new ChatAppBroadcastScheduler(worker, scheduler, 1000L);

        task.pollOnce();

        var ordered = inOrder(worker);
        ordered.verify(worker).recoverExpiredSubmissions();
        ordered.verify(worker).runAvailable(
                argThat(value -> value.startsWith("chatapp-broadcast-")), eq(10));
    }
}
