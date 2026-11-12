package com.crmforlogistics.messagecenter.service.chatapp.broadcast;

import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.AggregateResult;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.BroadcastStatus;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.RecipientStatus;

import java.util.List;

public final class ChatAppBroadcastStateMachine {
    private ChatAppBroadcastStateMachine() {
    }

    public static AggregateResult aggregate(List<RecipientStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            throw new IllegalArgumentException("CHATAPP_BROADCAST_RECIPIENTS_REQUIRED");
        }
        int success = 0;
        int failed = 0;
        int processing = 0;
        for (RecipientStatus status : statuses) {
            if (isSuccess(status)) {
                success++;
            } else if (status == RecipientStatus.FAILED_RECIPIENT) {
                failed++;
            } else {
                processing++;
            }
        }
        BroadcastStatus aggregateStatus;
        if (processing > 0) {
            aggregateStatus = BroadcastStatus.RECONCILING;
        } else if (failed == 0) {
            aggregateStatus = BroadcastStatus.SUCCEEDED;
        } else if (success == 0) {
            aggregateStatus = BroadcastStatus.FAILED;
        } else {
            aggregateStatus = BroadcastStatus.PARTIALLY_FAILED;
        }
        return new AggregateResult(aggregateStatus, success, failed, processing);
    }

    public static RecipientStatus advanceRecipient(RecipientStatus current, RecipientStatus next) {
        if (current == null) return next;
        if (next == null) return current;
        if (current == RecipientStatus.READ) {
            return current;
        }
        return rank(next) >= rank(current) ? next : current;
    }

    private static boolean isSuccess(RecipientStatus status) {
        return status == RecipientStatus.SENT
                || status == RecipientStatus.DELIVERED
                || status == RecipientStatus.READ;
    }

    private static int rank(RecipientStatus status) {
        return switch (status) {
            case QUEUED -> 0;
            case PROCESSING -> 1;
            case SENT -> 3;
            case FAILED_RECIPIENT -> 4;
            case DELIVERED -> 5;
            case READ -> 6;
        };
    }
}
