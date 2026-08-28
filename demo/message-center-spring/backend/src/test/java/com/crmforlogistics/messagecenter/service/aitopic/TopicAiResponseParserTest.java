package com.crmforlogistics.messagecenter.service.aitopic;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicAiResponseParserTest {
    @Test
    void rejectsUnknownSourceIds() {
        UUID allowed = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        String json = "{\"topics\":[{\"topicKey\":\"a\",\"title\":\"报价\",\"summary\":\"摘要\",\"relevance\":0.9,\"sourceIds\":[\"" + unknown + "\"]}]}";
        assertThatThrownBy(() -> new TopicAiResponseParser().parse(json, Set.of(allowed)))
                .isInstanceOf(AiTopicException.class)
                .hasMessage("AI_RESPONSE_INVALID");
    }

    @Test
    void parsesContentWrappedInMarkdownCodeFence() {
        UUID source = UUID.randomUUID();
        String json = "```json\n" +
                "{\"topics\":[{\"topicKey\":\"topic-1\",\"title\":\"报价\",\"summary\":\"摘要\",\"relevance\":0.9,\"sourceIds\":[\"" + source + "\"]}]}\n" +
                "```";
        var output = new TopicAiResponseParser().parse(json, Set.of(source));
        assertThat(output.assignments()).hasSize(1);
        assertThat(output.assignments().get(0).sourceIds()).containsExactly(source);
    }

    @Test
    void rejectsAssignmentsThatDoNotCoverEveryAllowedSource() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String json = "{\"topics\":[{\"topicKey\":\"a\",\"title\":\"报价\",\"summary\":\"摘要\",\"relevance\":0.9,\"sourceIds\":[\"" + first + "\"]}]}";
        assertThatThrownBy(() -> new TopicAiResponseParser().parse(json, Set.of(first, second)))
                .isInstanceOf(AiTopicException.class)
                .hasMessage("AI_RESPONSE_INVALID");
    }

    @Test
    void rejectsRepeatedTopicKeysToPreventDuplicateTopicWrites() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String json = "{\"topics\":[{\"topicKey\":\"same-topic\",\"title\":\"报价\",\"summary\":\"摘要\",\"relevance\":0.9,\"sourceIds\":[\"" + first + "\"]},"
                + "{\"topicKey\":\"same-topic\",\"title\":\"报价\",\"summary\":\"摘要\",\"relevance\":0.9,\"sourceIds\":[\"" + second + "\"]}]}";

        assertThatThrownBy(() -> new TopicAiResponseParser().parse(json, Set.of(first, second)))
                .isInstanceOf(AiTopicException.class)
                .hasMessage("AI_RESPONSE_INVALID");
    }
}
