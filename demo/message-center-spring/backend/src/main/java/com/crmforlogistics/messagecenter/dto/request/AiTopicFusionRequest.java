package com.crmforlogistics.messagecenter.dto.request;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AiTopicFusionRequest(List<UUID> topicIds, Map<UUID, Long> expectedVersions) {}
