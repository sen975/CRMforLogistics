package com.crmforlogistics.messagecenter.service.aitopic;

import java.util.UUID;

public interface AiTopicContactMergeReconciler {
    void reconcileAfterContactMerge(UUID sourceContactId, UUID targetContactId, UUID userId);
}
