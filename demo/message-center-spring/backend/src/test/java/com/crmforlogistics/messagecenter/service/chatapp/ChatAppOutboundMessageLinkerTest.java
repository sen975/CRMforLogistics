package com.crmforlogistics.messagecenter.service.chatapp;

import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastRecipientEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastRecipientMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppOutboundMessageLinker.LinkResult;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastMessageProjector;
import com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastMessageProjector.ReconciliationProjectionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.crmforlogistics.messagecenter.service.chatapp.ChatAppOutboundMessageLinker.LinkResult.Kind.AMBIGUOUS_BROADCAST;
import static com.crmforlogistics.messagecenter.service.chatapp.ChatAppOutboundMessageLinker.LinkResult.Kind.EXISTING_PROVIDER;
import static com.crmforlogistics.messagecenter.service.chatapp.ChatAppOutboundMessageLinker.LinkResult.Kind.ORPHAN;
import static com.crmforlogistics.messagecenter.service.chatapp.ChatAppOutboundMessageLinker.LinkResult.Kind.PROVIDER_CONFLICT;
import static com.crmforlogistics.messagecenter.service.chatapp.ChatAppOutboundMessageLinker.LinkResult.Kind.UNIQUE_BROADCAST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatAppOutboundMessageLinkerTest {
    private final UUID accountId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();
    private final Instant sentAt = Instant.parse("2026-08-17T10:00:00Z");
    private final Instant reconciledAt = Instant.parse("2026-08-17T11:00:00Z");
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final ChatAppBroadcastRecipientMapper recipientMapper =
            mock(ChatAppBroadcastRecipientMapper.class);
    private final ChatAppBroadcastMessageProjector messageProjector =
            mock(ChatAppBroadcastMessageProjector.class);
    private final ChatAppBroadcastRecipientEntity candidate = candidate();
    private final ChatAppBroadcastRecipientEntity firstCandidate = candidate();
    private final ChatAppBroadcastRecipientEntity secondCandidate = candidate();
    private final MessageEntity existingMessage = message();
    private final MessageEntity firstProviderMessage = message();
    private final MessageEntity secondProviderMessage = message();
    private ChatAppOutboundMessageLinker linker;
    private ListChatappMessageResponseBody.Data row;

    @BeforeEach
    void setUp() {
        linker = new ChatAppOutboundMessageLinker(
                messageMapper, recipientMapper, messageProjector,
                Clock.fixed(reconciledAt, ZoneOffset.UTC));
        row = ListChatappMessageResponseBody.Data.builder()
                .messageId("wamid-1")
                .uniqueMessageId("unique-1")
                .userNumber("60111111111")
                .templateCode("shipping_notice")
                .languageCode("zh_CN")
                .messageStatusName("DELIVERED")
                .sendTime(sentAt.toString())
                .build();
    }

    @Test
    void pollingLinksTheOnlyPendingBroadcastMessageInsteadOfImportingAnOrphan() {
        candidate.setMessageId(null);
        when(messageMapper.findAllByProviderMessageId(accountId, "wamid-1")).thenReturn(List.of());
        when(messageMapper.findByClientRequestId(accountId, "unique-1")).thenReturn(Optional.empty());
        when(recipientMapper.findPendingCandidates(
                accountId, "60111111111", "shipping_notice", "zh_CN", sentAt, 2))
                .thenReturn(List.of(candidate));
        when(messageProjector.ensureProcessing(candidate.getBroadcastId(), candidate.getId()))
                .thenReturn(new ChatAppBroadcastMessageProjector.ProjectionResult(
                        messageId, true, "processing"));
        when(messageProjector.applyReconciliation(
                eq(candidate.getBroadcastId()), eq(candidate.getId()), any(), eq(reconciledAt)))
                .thenReturn(new ReconciliationProjectionResult(messageId, true, true, ""));

        LinkResult result = linker.resolve(accountId, row);

        assertThat(result.kind()).isEqualTo(UNIQUE_BROADCAST);
        assertThat(result.messageId()).isEqualTo(messageId);
        InOrder order = inOrder(messageProjector);
        order.verify(messageProjector).ensureProcessing(candidate.getBroadcastId(), candidate.getId());
        order.verify(messageProjector).applyReconciliation(
                eq(candidate.getBroadcastId()), eq(candidate.getId()),
                org.mockito.ArgumentMatchers.argThat(item -> "wamid-1".equals(item.providerMessageId())
                        && "60111111111".equals(item.recipientNumber())), eq(reconciledAt));
    }

    @Test
    void multiplePendingBroadcastsStayAmbiguous() {
        when(messageMapper.findAllByProviderMessageId(accountId, "wamid-1")).thenReturn(List.of());
        when(messageMapper.findByClientRequestId(accountId, "unique-1")).thenReturn(Optional.empty());
        when(recipientMapper.findPendingCandidates(
                accountId, "60111111111", "shipping_notice", "zh_CN", sentAt, 2))
                .thenReturn(List.of(firstCandidate, secondCandidate));

        LinkResult result = linker.resolve(accountId, row);

        assertThat(result.kind()).isEqualTo(AMBIGUOUS_BROADCAST);
        verifyNoInteractions(messageProjector);
    }

    @Test
    void noPendingBroadcastReturnsOrphan() {
        when(messageMapper.findAllByProviderMessageId(accountId, "wamid-1")).thenReturn(List.of());
        when(messageMapper.findByClientRequestId(accountId, "unique-1")).thenReturn(Optional.empty());
        when(recipientMapper.findPendingCandidates(
                accountId, "60111111111", "shipping_notice", "zh_CN", sentAt, 2))
                .thenReturn(List.of());

        assertThat(linker.resolve(accountId, row).kind()).isEqualTo(ORPHAN);
    }

    @Test
    void existingProviderMessageWinsBeforeBroadcastCandidateLookup() {
        when(messageMapper.findAllByProviderMessageId(accountId, "wamid-1"))
                .thenReturn(List.of(existingMessage));

        LinkResult result = linker.resolve(accountId, row);

        assertThat(result.kind()).isEqualTo(EXISTING_PROVIDER);
        verify(recipientMapper, never()).findPendingCandidates(
                any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void duplicateProviderIdStopsBeforeAnyCandidateGuess() {
        when(messageMapper.findAllByProviderMessageId(accountId, "wamid-1"))
                .thenReturn(List.of(firstProviderMessage, secondProviderMessage));

        LinkResult result = linker.resolve(accountId, row);

        assertThat(result.kind()).isEqualTo(PROVIDER_CONFLICT);
        assertThat(result.reason()).isEqualTo("CHATAPP_PROVIDER_MESSAGE_ID_CONFLICT");
        verify(recipientMapper, never()).findPendingCandidates(
                any(), any(), any(), any(), any(), anyInt());
        verifyNoInteractions(messageProjector);
    }

    private static ChatAppBroadcastRecipientEntity candidate() {
        ChatAppBroadcastRecipientEntity entity = new ChatAppBroadcastRecipientEntity();
        entity.setId(UUID.randomUUID());
        entity.setBroadcastId(UUID.randomUUID());
        entity.setStatus("PROCESSING");
        return entity;
    }

    private static MessageEntity message() {
        MessageEntity entity = new MessageEntity();
        entity.setId(UUID.randomUUID());
        return entity;
    }
}
