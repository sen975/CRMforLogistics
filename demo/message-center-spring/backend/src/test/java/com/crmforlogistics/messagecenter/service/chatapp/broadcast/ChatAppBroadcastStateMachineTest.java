package com.crmforlogistics.messagecenter.service.chatapp.broadcast;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.BroadcastStatus.FAILED;
import static com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.BroadcastStatus.PARTIALLY_FAILED;
import static com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.BroadcastStatus.RECONCILING;
import static com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.BroadcastStatus.SUCCEEDED;
import static com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientStatus.DELIVERED;
import static com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientStatus.FAILED_RECIPIENT;
import static com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientStatus.PROCESSING;
import static com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientStatus.READ;
import static com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientStatus.SENT;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatAppBroadcastStateMachineTest {

    @Test
    void allSuccessfulRecipientsCompleteTheBroadcast() {
        var aggregate = ChatAppBroadcastStateMachine.aggregate(List.of(SENT, DELIVERED, READ));

        assertEquals(SUCCEEDED, aggregate.status());
        assertEquals(3, aggregate.successCount());
        assertEquals(0, aggregate.failedCount());
        assertEquals(0, aggregate.processingCount());
    }

    @Test
    void mixedTerminalResultsArePartiallyFailed() {
        var aggregate = ChatAppBroadcastStateMachine.aggregate(List.of(DELIVERED, FAILED_RECIPIENT));

        assertEquals(PARTIALLY_FAILED, aggregate.status());
        assertEquals(1, aggregate.successCount());
        assertEquals(1, aggregate.failedCount());
        assertEquals(0, aggregate.processingCount());
    }

    @Test
    void allFailedRecipientsFailTheBroadcast() {
        var aggregate = ChatAppBroadcastStateMachine.aggregate(List.of(FAILED_RECIPIENT, FAILED_RECIPIENT));

        assertEquals(FAILED, aggregate.status());
        assertEquals(0, aggregate.successCount());
        assertEquals(2, aggregate.failedCount());
        assertEquals(0, aggregate.processingCount());
    }

    @Test
    void anyNonTerminalRecipientKeepsReconciliationOpen() {
        var aggregate = ChatAppBroadcastStateMachine.aggregate(List.of(DELIVERED, PROCESSING));

        assertEquals(RECONCILING, aggregate.status());
        assertEquals(1, aggregate.successCount());
        assertEquals(0, aggregate.failedCount());
        assertEquals(1, aggregate.processingCount());
    }

    @Test
    void confirmedDeliveryCanCorrectFailureAndDoesNotRegress() {
        assertEquals(DELIVERED,
                ChatAppBroadcastStateMachine.advanceRecipient(FAILED_RECIPIENT, DELIVERED));
        assertEquals(READ,
                ChatAppBroadcastStateMachine.advanceRecipient(FAILED_RECIPIENT, READ));
        assertEquals(READ,
                ChatAppBroadcastStateMachine.advanceRecipient(DELIVERED, READ));
        assertEquals(READ,
                ChatAppBroadcastStateMachine.advanceRecipient(READ, FAILED_RECIPIENT));
    }

    @Test
    void failureAndSentUseTheSameOrderAsOutboundMessages() {
        assertEquals(FAILED_RECIPIENT,
                ChatAppBroadcastStateMachine.advanceRecipient(FAILED_RECIPIENT, SENT));
        assertEquals(FAILED_RECIPIENT,
                ChatAppBroadcastStateMachine.advanceRecipient(SENT, FAILED_RECIPIENT));
    }
}
