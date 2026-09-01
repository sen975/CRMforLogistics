package com.crmforlogistics.messagecenter.service.aitopic;

import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.GenerationInput;
import com.crmforlogistics.messagecenter.service.aitopic.AiTopicModels.GenerationOutput;

public interface TopicAiGateway {
    GenerationOutput generate(GenerationInput input);

    default GenerationOutput generate(GenerationInput input, AiTopicGenerationAuditService.Context auditContext) {
        return generate(input);
    }
}
