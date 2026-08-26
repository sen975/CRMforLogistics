package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeComChatDataNormalizerTest {
    private final WeComChatDataNormalizer normalizer = new WeComChatDataNormalizer();
    private final ResolvedInstallation installation =
            new ResolvedInstallation("installation", "suite", "corp", "agent", "permanent", 1L);

    @Test
    void normalizesEmployeeExternalDirectConversationWithStablePartyOrder() {
        var result = normalizer.normalize(installation,
                new WeComChatDataGateway.EncryptedMessage("m1",
                        new WeComChatDataGateway.Party(1, "employee"),
                        List.of(new WeComChatDataGateway.Party(2, "external")), "", 10L, 1, "key", 1), "secret");

        assertThat(result.conversationType()).isEqualTo("DIRECT");
        assertThat(result.providerConversationKey()).isEqualTo("EMPLOYEE:employee:EXTERNAL_CONTACT:external");
        assertThat(result.sender().partyType()).isEqualTo("EMPLOYEE");
        assertThat(result.contactParty()).isEqualTo(new WeComChatDataNormalizer.PartyRef(
                "EXTERNAL_CONTACT", "external"));
    }

    @Test
    void usesEmployeeReceiverAsContactPartyForExternalToEmployeeDirectConversation() {
        var result = normalizer.normalize(installation,
                new WeComChatDataGateway.EncryptedMessage("m1b",
                        new WeComChatDataGateway.Party(2, "external"),
                        List.of(new WeComChatDataGateway.Party(1, "employee")), "", 10L, 1, "key", 1), "secret");

        assertThat(result.conversationType()).isEqualTo("DIRECT");
        assertThat(result.contactParty()).isEqualTo(new WeComChatDataNormalizer.PartyRef(
                "EXTERNAL_CONTACT", "external"));
    }

    @Test
    void chatIdAlwaysCreatesGroupConversationEvenWhenReceiversArePresent() {
        var result = normalizer.normalize(installation,
                new WeComChatDataGateway.EncryptedMessage("m2",
                        new WeComChatDataGateway.Party(1, "employee"),
                        List.of(new WeComChatDataGateway.Party(1, "member"),
                                new WeComChatDataGateway.Party(2, "external")), "wr-group", 10L, 1, "key", 1), "secret");

        assertThat(result.conversationType()).isEqualTo("GROUP");
        assertThat(result.providerConversationKey()).isEqualTo("group:wr-group");
        assertThat(result.receivers()).hasSize(2);
        assertThat(result.contactParty()).isNull();
    }

    @Test
    void normalizesRobotPartyAsGroupParticipantInsteadOfRejectingTheMessage() {
        var result = normalizer.normalize(installation,
                new WeComChatDataGateway.EncryptedMessage("m-robot",
                        new WeComChatDataGateway.Party(3, "robot-id"),
                        List.of(new WeComChatDataGateway.Party(1, "employee")),
                        "wr-group", 10L, 1, "key", 1), "secret");

        assertThat(result.sender().partyType()).isEqualTo("ROBOT");
        assertThat(result.conversationType()).isEqualTo("GROUP");
    }

    @Test
    void rejectsMissingReceiverAndUnsupportedPartyTypeAsStructuredErrors() {
        assertThatThrownBy(() -> normalizer.normalize(installation,
                new WeComChatDataGateway.EncryptedMessage("m3",
                        new WeComChatDataGateway.Party(1, "employee"), List.of(), "", 10L, 1, "key", 1), "secret"))
                .isInstanceOf(WeComChatDataException.class)
                .extracting("code").isEqualTo("WECOM_CHATDATA_RECEIVER_MISSING");
        assertThatThrownBy(() -> normalizer.normalize(installation,
                new WeComChatDataGateway.EncryptedMessage("m4",
                        new WeComChatDataGateway.Party(9, "unknown"), List.of(new WeComChatDataGateway.Party(1, "member")), "", 10L, 1, "key", 1), "secret"))
                .isInstanceOf(WeComChatDataException.class)
                .extracting("code").isEqualTo("WECOM_CHATDATA_PARTY_TYPE_UNSUPPORTED");
        assertThatThrownBy(() -> normalizer.normalize(installation,
                new WeComChatDataGateway.EncryptedMessage("m-robot-direct",
                        new WeComChatDataGateway.Party(3, "robot"),
                        List.of(new WeComChatDataGateway.Party(1, "employee")), "", 10L, 1, "key", 1), "secret"))
                .isInstanceOf(WeComChatDataException.class)
                .extracting("code").isEqualTo("WECOM_CHATDATA_ROBOT_DIRECT_UNSUPPORTED");
    }
}
