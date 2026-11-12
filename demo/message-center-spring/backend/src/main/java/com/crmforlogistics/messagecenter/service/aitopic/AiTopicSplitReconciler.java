package com.crmforlogistics.messagecenter.service.aitopic;

import java.util.UUID;

/** Recomputes an active Topic after contact splitting removed some of its sources. */
public interface AiTopicSplitReconciler {
    void recomputeAfterSourceSplit(UUID topicId);
}
