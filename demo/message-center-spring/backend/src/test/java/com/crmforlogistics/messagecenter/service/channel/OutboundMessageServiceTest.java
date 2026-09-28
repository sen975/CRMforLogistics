package com.crmforlogistics.messagecenter.service.channel;

import com.crmforlogistics.messagecenter.channel.email.EmailException;
import com.crmforlogistics.messagecenter.channel.email.EmailSendService;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMessageApplicationService;
import com.crmforlogistics.messagecenter.service.chatapp.outbox.MessageSendApplicationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 出站发送的收件人解析与两个渠道的转发。
 *
 * <h1>1. 归属必须一路带到 SQL</h1>
 * 这个类唯一的防线是「用哪个 {@code userId} 去查」—— 解析出来的地址会直接用于发送，
 * 所以一旦有人图省事写死一个 {@code null} 或忘了透传，越权就成立了，而测试之外看不出来。
 * 于是这里断的不是「拿到了地址」，而是「查询收到了调用方的 id」。
 *
 * <h1>2. 「拿不到地址」必须是拿不到，而不是空串</h1>
 * 返回空串会让 SMTP 去解析一个空收件人，或者在 chatapp 那条路上变成
 * {@code CHATAPP_RECIPIENT_REQUIRED} —— 两者都把「这个人档案里没有地址」报成一个
 * 与用户输入无关的内部错误。这里要求它是 {@code null} / 明确的
 * {@link OutboundException#RECIPIENT_MISSING}。
 *
 * <h1>3. 失败要按「用户能不能改参数」分档，而不是按底层组件</h1>
 * 三个码分别对应三种处置：补档案（无地址）、找管理员（渠道不可用）、
 * <b>先去确认再说、绝不要重发</b>（结果未知）。最后一个尤其不能退回成通用失败 ——
 * 那会让模型自己去重试，而重试就是真的又发一封信。
 */
class OutboundMessageServiceTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTACT = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID EMAIL_IDENTITY = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID CHATAPP_IDENTITY = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    private final ContactIdentityMapper identities = mock(ContactIdentityMapper.class);
    private final EmailSendService emails = mock(EmailSendService.class);
    private final ChatAppMessageApplicationService chatApp = mock(ChatAppMessageApplicationService.class);
    private final OutboundMessageService service = new OutboundMessageService(identities, emails, chatApp);

    // ---------- 解析 ----------

    @Test
    void theCallersIdentityIsWhatTheSqlIsGivenSoThereIsNoSecondPermissionCheckToForget() {
        when(identities.findByContactIdAndOwner(CONTACT, USER))
                .thenReturn(List.of(email(EMAIL_IDENTITY, "zhou@example.com", "zhou@example.com")));

        service.resolve(USER, CONTACT, OutboundMessageService.CHANNEL_EMAIL);

        verify(identities).findByContactIdAndOwner(CONTACT, USER);
    }

    @Test
    void theFirstMatchingIdentityWinsBecauseTheSqlAlreadyPutThePrimaryOneFirst() {
        // 主地址的选择权不在这一层：SQL 已经按 is_primary desc, created_at, id 排好序，
        // 这里再排一次就等于偷偷定义第二套「主地址」规则，而界面上用的是另一套。
        when(identities.findByContactIdAndOwner(CONTACT, USER)).thenReturn(List.of(
                email(UUID.randomUUID(), "primary@example.com", "primary@example.com"),
                email(UUID.randomUUID(), "second@example.com", "second@example.com")));

        assertThat(service.resolve(USER, CONTACT, OutboundMessageService.CHANNEL_EMAIL).address())
                .isEqualTo("primary@example.com");
    }

    @Test
    void identitiesOfAnotherChannelAreNotEligible() {
        when(identities.findByContactIdAndOwner(CONTACT, USER))
                .thenReturn(List.of(phone(CHATAPP_IDENTITY, "13800000000")));

        assertThat(service.resolve(USER, CONTACT, OutboundMessageService.CHANNEL_EMAIL))
                .as("这个人只有一个手机号身份，所以「发邮件给他」应当解析不到收件人")
                .isNull();
    }

    @Test
    void anEmailAddressIsNormalisedExactlyLikeTheSendPathDoes() {
        // EmailSendService 写身份时用的就是 extractEmail 作为归一化键，所以两边同源。
        // 身份值可能是「王工 <wang@x.com>」这种带显示名的形态，直接用会把显示名一起发出去。
        when(identities.findByContactIdAndOwner(CONTACT, USER))
                .thenReturn(List.of(email(EMAIL_IDENTITY, "王工 <Wang@Example.COM>", null)));
        assertThat(service.resolve(USER, CONTACT, OutboundMessageService.CHANNEL_EMAIL).address())
                .isEqualTo("wang@example.com");

        // 有归一化值时以它为准（那是独立的列，形态可能与 identity_value 不同）。
        when(identities.findByContactIdAndOwner(CONTACT, USER))
                .thenReturn(List.of(email(EMAIL_IDENTITY, "王工 <Wang@Example.COM>", "wang@example.com")));
        assertThat(service.resolve(USER, CONTACT, OutboundMessageService.CHANNEL_EMAIL).address())
                .isEqualTo("wang@example.com");
    }

    @Test
    void aPhoneIsStrippedExactlyLikeTheChatAppSendPathDoes() {
        // 表达式必须与 ChatAppMessageApplicationService.recipientForAccount 一致 ——
        // 那条路会用归一化后的号码发送，而卡片上显示的是这里算出来的值。
        when(identities.findByContactIdAndOwner(CONTACT, USER))
                .thenReturn(List.of(phone(CHATAPP_IDENTITY, "+86 138 0000 0000")));

        assertThat(service.resolve(USER, CONTACT, OutboundMessageService.CHANNEL_CHATAPP).address())
                .isEqualTo("8613800000000");
    }

    @Test
    void anIdentityWithoutAUsableAddressIsSkippedInsteadOfBecomingAnEmptyRecipient() {
        when(identities.findByContactIdAndOwner(CONTACT, USER)).thenReturn(List.of(
                email(UUID.randomUUID(), "   ", "   "),
                email(EMAIL_IDENTITY, "zhou@example.com", "zhou@example.com")));

        assertThat(service.resolve(USER, CONTACT, OutboundMessageService.CHANNEL_EMAIL).address())
                .as("空地址那一条要被跳过，而不是让它成为收件人")
                .isEqualTo("zhou@example.com");

        when(identities.findByContactIdAndOwner(CONTACT, USER))
                .thenReturn(List.of(phone(CHATAPP_IDENTITY, "  ")));
        assertThat(service.resolve(USER, CONTACT, OutboundMessageService.CHANNEL_CHATAPP))
                .as("全是空白号码时返回 null（去掉分隔符就什么都不剩）")
                .isNull();
    }

    // ---------- 邮件 ----------

    @Test
    void sendEmailUsesTheOwnerScopedOverloadAndTheResolvedAddress() throws Exception {
        when(identities.findByContactIdAndOwner(CONTACT, USER))
                .thenReturn(List.of(email(EMAIL_IDENTITY, "zhou@example.com", "zhou@example.com")));
        // 渠道侧返回的仍然是它自己的类型 —— 本类负责把它映射成 service 层的回执。
        when(emails.send(eq(USER), anyString(), anyString(), anyString())).thenReturn(
                new EmailSendService.SendResult("msg-1", "me@mycorp.com", "zhou@example.com", "报价确认", "sent"));

        OutboundMessageService.EmailReceipt result = service.sendEmail(USER, CONTACT, "报价确认", "正文在此");

        // owner 重载（send(UUID, …)）会先做 requireOwnedEmailAccount —— 走的不是那条无主的兼容路径。
        verify(emails).send(USER, "zhou@example.com", "报价确认", "正文在此");
        assertThat(result.to()).isEqualTo("zhou@example.com");
        // 映射不能漏字段：漏一个也只是回话里少一个值，肉眼很难发现。
        assertThat(result.status()).isEqualTo("sent");
        assertThat(result.messageId()).isEqualTo("msg-1");
    }

    @Test
    void sendEmailNeverReachesTheTransportWhenThereIsNoAddress() {
        when(identities.findByContactIdAndOwner(CONTACT, USER)).thenReturn(List.of());

        Throwable failure = catchThrowable(() -> service.sendEmail(USER, CONTACT, "主题", "正文"));

        assertThat(failure).isInstanceOf(OutboundException.class);
        assertThat(((OutboundException) failure).code()).isEqualTo(OutboundException.RECIPIENT_MISSING);
        // 关键的一半：不能「先拿空地址试一次，失败了再报没有地址」。
        verifyNoInteractions(emails);
    }

    @Test
    void anUnknownSmtpOutcomeKeepsItsOwnCodeBecauseRetryingItSendsASecondLetter() throws Exception {
        when(identities.findByContactIdAndOwner(CONTACT, USER))
                .thenReturn(List.of(email(EMAIL_IDENTITY, "zhou@example.com", "zhou@example.com")));
        when(emails.send(any(), anyString(), anyString(), anyString()))
                .thenThrow(new EmailException("EMAIL_SEND_OUTCOME_UNKNOWN", "SMTP accepted but state unknown"));

        Throwable failure = catchThrowable(() -> service.sendEmail(USER, CONTACT, "主题", "正文"));

        assertThat(failure).isInstanceOf(OutboundException.class);
        assertThat(((OutboundException) failure).code())
                .as("并进通用失败会让调用方去重试，而重试就是真的再发一封")
                .isEqualTo(OutboundException.OUTCOME_UNKNOWN);
    }

    @Test
    void aMissingMailAccountIsAChannelProblemNotARecipientProblem() throws Exception {
        when(identities.findByContactIdAndOwner(CONTACT, USER))
                .thenReturn(List.of(email(EMAIL_IDENTITY, "zhou@example.com", "zhou@example.com")));
        when(emails.send(any(), anyString(), anyString(), anyString())).thenThrow(
                new EmailException("CHANNEL_ACCOUNT_REQUIRED", "Exactly one active email account is required"));

        Throwable failure = catchThrowable(() -> service.sendEmail(USER, CONTACT, "主题", "正文"));

        assertThat(failure).isInstanceOf(OutboundException.class);
        assertThat(((OutboundException) failure).code())
                .as("报成「没有地址」会让用户去改一个本来就对的东西")
                .isEqualTo(OutboundException.CHANNEL_UNAVAILABLE);
    }

    @Test
    void anUnrecognisedMailFailureFallsBackToTheGenericCodeInsteadOfGuessing() throws Exception {
        when(identities.findByContactIdAndOwner(CONTACT, USER))
                .thenReturn(List.of(email(EMAIL_IDENTITY, "zhou@example.com", "zhou@example.com")));
        when(emails.send(any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("boom"));

        Throwable failure = catchThrowable(() -> service.sendEmail(USER, CONTACT, "主题", "正文"));

        assertThat(failure).isInstanceOf(OutboundException.class);
        assertThat(((OutboundException) failure).code()).isEqualTo(OutboundException.SEND_FAILED);
    }

    // ---------- chatapp ----------

    @Test
    void chatAppSendsTheResolvedIdentityWithAFreshServerSideRequestId() {
        when(identities.findByContactIdAndOwner(CONTACT, USER))
                .thenReturn(List.of(phone(CHATAPP_IDENTITY, "13800000000")));
        when(chatApp.acceptContactIdentity(any(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(new MessageSendApplicationService.MessageAccepted(UUID.randomUUID(), "pending", false));

        service.sendChatApp(USER, CONTACT, "货已发出");
        service.sendChatApp(USER, CONTACT, "货已发出");

        ArgumentCaptor<String> requestIds = ArgumentCaptor.forClass(String.class);
        verify(chatApp, times(2)).acceptContactIdentity(
                eq(CONTACT), eq(CHATAPP_IDENTITY), eq("text"), requestIds.capture(),
                eq(Map.of("text", "货已发出")), eq(USER));

        assertThat(requestIds.getAllValues())
                .as("幂等键由服务端生成且每次不同 —— 复用它会让去重静默吞掉第二次发送（返回 duplicate=true 却什么都没发）")
                .hasSize(2)
                .doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(UUID.fromString(id)).isNotNull());
    }

    @Test
    void anyChatAppFailureIsReportedAsAChannelProblemBecauseTheRecipientWasAlreadyResolved() {
        when(identities.findByContactIdAndOwner(CONTACT, USER))
                .thenReturn(List.of(phone(CHATAPP_IDENTITY, "13800000000")));
        when(chatApp.acceptContactIdentity(any(), any(), anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalArgumentException("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE"));

        Throwable failure = catchThrowable(() -> service.sendChatApp(USER, CONTACT, "你好"));

        assertThat(failure).isInstanceOf(OutboundException.class);
        assertThat(((OutboundException) failure).code())
                .as("收件人已经在上面解析过了，所以剩下的失败都在账号/授权那一侧")
                .isEqualTo(OutboundException.CHANNEL_UNAVAILABLE);
        verifyNoInteractions(emails);
    }

    // ---------- 夹具 ----------

    private static ContactIdentityEntity email(UUID id, String value, String normalized) {
        return identity(id, "email", value, normalized);
    }

    private static ContactIdentityEntity phone(UUID id, String value) {
        return identity(id, "chatapp", value, null);
    }

    private static ContactIdentityEntity identity(UUID id, String channelType, String value, String normalized) {
        ContactIdentityEntity entity = new ContactIdentityEntity();
        entity.setId(id);
        entity.setContactId(CONTACT);
        entity.setChannelType(channelType);
        entity.setIdentityValue(value);
        entity.setNormalizedValue(normalized);
        return entity;
    }
}
