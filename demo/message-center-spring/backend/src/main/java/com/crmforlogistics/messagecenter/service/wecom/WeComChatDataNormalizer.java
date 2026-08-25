package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataGateway;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Converts the provider envelope into a stable source conversation contract. */
@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComChatDataNormalizer {
    public NormalizedWeComMessage normalize(ResolvedInstallation installation,
                                             WeComChatDataGateway.EncryptedMessage message,
                                             String secretKey) {
        if (installation == null || message == null || secretKey == null || secretKey.isBlank()) {
            throw invalid("WECOM_CHATDATA_MESSAGE_INVALID");
        }
        PartyRef sender = party(message.sender());
        List<PartyRef> receivers = message.receivers() == null ? List.of()
                : message.receivers().stream().map(this::party).toList();
        if (receivers.isEmpty()) throw invalid("WECOM_CHATDATA_RECEIVER_MISSING");

        String type;
        String conversationKey;
        if (message.chatId() != null && !message.chatId().isBlank()) {
            if (message.chatId().length() > 256) throw invalid("WECOM_CHATDATA_CHAT_ID_INVALID");
            type = "GROUP";
            conversationKey = "group:" + message.chatId();
        } else {
            if (receivers.size() != 1) throw invalid("WECOM_CHATDATA_DIRECT_PARTICIPANTS_INVALID");
            type = "DIRECT";
            conversationKey = Stream.concat(Stream.of(sender), receivers.stream())
                    .sorted(Comparator.comparing(PartyRef::stableKey))
                    .map(PartyRef::stableKey)
                    .reduce((left, right) -> left + ":" + right)
                    .orElseThrow(() -> invalid("WECOM_CHATDATA_DIRECT_PARTICIPANTS_INVALID"));
        }
        if (message.msgid() == null || message.msgid().isBlank() || message.msgid().length() > 256
                || message.sendTime() < 0 || message.msgType() < 0) {
            throw invalid("WECOM_CHATDATA_MESSAGE_INVALID");
        }
        PartyRef contactParty = "DIRECT".equals(type)
                ? (sender.partyType().equals("EMPLOYEE") ? receivers.get(0) : sender)
                : null;
        return new NormalizedWeComMessage(installation.installationId(), installation.authCorpId(),
                message.msgid(), secretKey, sender, List.copyOf(receivers), type, conversationKey,
                contactParty, message.sendTime(), message.msgType());
    }

    private PartyRef party(WeComChatDataGateway.Party party) {
        if (party == null || party.id() == null || party.id().isBlank() || party.id().length() > 128) {
            throw invalid("WECOM_CHATDATA_PARTY_INVALID");
        }
        String type = switch (party.type()) {
            case 1 -> "EMPLOYEE";
            case 2 -> "EXTERNAL_CONTACT";
            default -> throw invalid("WECOM_CHATDATA_PARTY_TYPE_UNSUPPORTED");
        };
        return new PartyRef(type, party.id());
    }

    private static WeComChatDataException invalid(String code) {
        return new WeComChatDataException(code, 422, "企业微信会话消息不符合源会话合同");
    }

    public record PartyRef(String partyType, String providerPartyId) {
        public PartyRef {
            Objects.requireNonNull(partyType);
            Objects.requireNonNull(providerPartyId);
        }

        public String stableKey() { return partyType + ":" + providerPartyId; }
    }

    public record NormalizedWeComMessage(String installationId, String authCorpId, String msgid,
                                         String secretKey, PartyRef sender, List<PartyRef> receivers,
                                         String conversationType, String providerConversationKey,
                                         PartyRef contactParty, long sendTime, int msgType) {}
}
