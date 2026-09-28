package com.crmforlogistics.messagecenter.service.assistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantTokenEstimatorTest {

    @Test
    void countsCjkAndAsciiUsingConfiguredEncoding() {
        AssistantTokenEstimator estimator = new AssistantTokenEstimator("o200k_base");

        assertThat(estimator.count("hello world")).isGreaterThan(0);
        assertThat(estimator.count("客户要求明天上午十点回访")).isGreaterThan(0);
        assertThat(estimator.count("客户要求明天上午十点回访"))
                .isNotEqualTo("客户要求明天上午十点回访".length());
    }

    @Test
    void rejectsUnsupportedEncodingInsteadOfPretendingPrecision() {
        assertThatThrownBy(() -> new AssistantTokenEstimator("made_up_encoding"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encoding");
    }
}
