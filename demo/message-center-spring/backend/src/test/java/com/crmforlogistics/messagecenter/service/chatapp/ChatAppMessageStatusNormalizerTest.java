package com.crmforlogistics.messagecenter.service.chatapp;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAppMessageStatusNormalizerTest {

    @ParameterizedTest
    @ValueSource(strings = {"Success", "Successful", "Succeeded", "OK"})
    void providerAcceptedStatusesAreProjectedAsSubmitted(String upstreamStatus) {
        assertThat(ChatAppMessageStatusNormalizer.normalize(upstreamStatus))
                .isEqualTo("submitted");
    }
}
