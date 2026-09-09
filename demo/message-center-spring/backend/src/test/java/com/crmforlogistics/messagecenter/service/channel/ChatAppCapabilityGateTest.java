package com.crmforlogistics.messagecenter.service.channel;

import com.crmforlogistics.messagecenter.mapper.ChatAppCapabilityResultMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAppCapabilityGateTest {
    @Test
    void blocksWhatsappUntilTheLatestReadOnlyReportIsReady() {
        ChatAppCapabilityResultMapper mapper = mock(ChatAppCapabilityResultMapper.class);
        when(mapper.latestReadOnlyReady()).thenReturn(false);
        ChatAppCapabilityGate gate = new ChatAppCapabilityGate(mapper);

        assertThat(gate.status().ready()).isFalse();
        assertThatThrownBy(gate::requireReady)
                .isInstanceOf(ChannelAccountException.class)
                .hasMessage("WHATSAPP_CAPABILITY_NOT_VERIFIED");
    }

    @Test
    void allowsWhatsappAfterTheLatestReadOnlyReportIsReady() {
        ChatAppCapabilityResultMapper mapper = mock(ChatAppCapabilityResultMapper.class);
        when(mapper.latestReadOnlyReady()).thenReturn(true);

        ChatAppCapabilityGate gate = new ChatAppCapabilityGate(mapper);

        assertThat(gate.status().ready()).isTrue();
        gate.requireReady();
    }
}
