package com.crmforlogistics.messagecenter.service.chatapp;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAppOutboundMessageStateMachineTest {

    @ParameterizedTest
    @CsvSource({
            "pending,processing,processing",
            "processing,submitted,submitted",
            "processing,sent,sent",
            "sent,delivered,delivered",
            "delivered,read,read",
            "read,delivered,read",
            "delivered,failed,delivered",
            "failed,delivered,delivered"
    })
    void advancesWithoutRegressingConfirmedDelivery(
            String current, String next, String expected) {
        assertThat(ChatAppOutboundMessageStateMachine.advance(current, next)).isEqualTo(expected);
    }
}
