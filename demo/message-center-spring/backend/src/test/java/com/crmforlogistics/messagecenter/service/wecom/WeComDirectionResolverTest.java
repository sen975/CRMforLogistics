package com.crmforlogistics.messagecenter.service.wecom;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class WeComDirectionResolverTest {

    @Test
    void treatsCurrentEmployeeAsOutboundAndOtherEmployeeAsInbound() {
        var viewer = new WeComChatDataNormalizer.PartyRef("EMPLOYEE", "employee-a");
        var other = new WeComChatDataNormalizer.PartyRef("EMPLOYEE", "employee-b");

        assertThat(WeComDirectionResolver.resolve("employee-a", viewer, List.of(other)))
                .isEqualTo("outbound");
        assertThat(WeComDirectionResolver.resolve("employee-a", other, List.of(viewer)))
                .isEqualTo("inbound");
    }

    @Test
    void refusesToInventDirectionWhenViewerIsNotParticipant() {
        var sender = new WeComChatDataNormalizer.PartyRef("EMPLOYEE", "employee-b");
        var receiver = new WeComChatDataNormalizer.PartyRef("EMPLOYEE", "employee-c");

        assertThat(WeComDirectionResolver.resolve("employee-a", sender, List.of(receiver)))
                .isEqualTo("unknown");
    }
}
