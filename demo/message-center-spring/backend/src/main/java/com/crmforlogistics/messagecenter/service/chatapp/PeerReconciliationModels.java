package com.crmforlogistics.messagecenter.service.chatapp;

import java.util.UUID;

public final class PeerReconciliationModels {
    private PeerReconciliationModels() {
    }

    public record PeerReconciliationCommand(
            UUID channelAccountId,
            UUID messageId,
            String providerMessageId,
            String userNumber) {
    }

    public record PeerReconciliationResult(
            Kind kind,
            UUID identityId,
            UUID conversationId,
            String reason,
            boolean identityCreated) {
        public enum Kind {
            UNCHANGED,
            MOVED,
            UNRESOLVED
        }

        public static PeerReconciliationResult unchanged(UUID identityId, UUID conversationId) {
            return new PeerReconciliationResult(
                    Kind.UNCHANGED, identityId, conversationId, "", false);
        }

        public static PeerReconciliationResult moved(UUID identityId, UUID conversationId) {
            return moved(identityId, conversationId, false);
        }

        public static PeerReconciliationResult moved(
                UUID identityId, UUID conversationId, boolean identityCreated) {
            return new PeerReconciliationResult(
                    Kind.MOVED, identityId, conversationId, "", identityCreated);
        }

        public static PeerReconciliationResult unresolved(String reason) {
            return new PeerReconciliationResult(
                    Kind.UNRESOLVED, null, null, reason, false);
        }
    }
}
