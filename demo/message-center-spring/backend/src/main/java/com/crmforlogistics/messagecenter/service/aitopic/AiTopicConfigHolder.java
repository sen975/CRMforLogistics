package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.config.AiTopicConfig;
import org.springframework.stereotype.Component;

@Component
public class AiTopicConfigHolder {
    private final AiTopicConfig config;
    public AiTopicConfigHolder(AiTopicConfig config) { this.config = config; }
    public AiTopicConfig get() { return config; }
}
