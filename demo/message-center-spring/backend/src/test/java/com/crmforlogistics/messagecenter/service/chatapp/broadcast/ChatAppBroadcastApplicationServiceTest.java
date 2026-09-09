package com.crmforlogistics.messagecenter.service.chatapp.broadcast;

import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastJobEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastRecipientEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppBroadcastReconciliationEvidenceEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.dto.response.TemplateResponse;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastJobMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastReconciliationEvidenceMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppBroadcastRecipientMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppAccountResolver;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.crmforlogistics.messagecenter.service.chatapp.broadcast.ChatAppBroadcastModels.BroadcastStatus.QUEUED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class ChatAppBroadcastApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-14T06:00:00Z");
    private static final UUID SCOPE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Mock ChatAppBroadcastMapper broadcastMapper;
    @Mock ChatAppBroadcastRecipientMapper recipientMapper;
    @Mock ChatAppBroadcastJobMapper jobMapper;
    @Mock ChatAppBroadcastReconciliationEvidenceMapper evidenceMapper;
    @Mock ContactIdentityMapper identityMapper;
    @Mock TemplateMapper templateMapper;
    @Mock ChatAppAccountResolver accountResolver;
    @Mock ChatAppTemplateService chatAppTemplateService;

    private ChatAppBroadcastApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ChatAppBroadcastApplicationService(
                broadcastMapper, recipientMapper, jobMapper, evidenceMapper, identityMapper,
                templateMapper, accountResolver, chatAppTemplateService, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(broadcastMapper.insertIfAbsent(any(ChatAppBroadcastEntity.class))).thenReturn(1);
    }

    @Test
    void sendableTemplatesRejectAnotherUsersAccountBeforeReadingBusinessData() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(accountResolver.requireOwnedAccount(actorId, accountId))
                .thenThrow(new IllegalArgumentException("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE"));

        assertThatThrownBy(() -> service.sendableTemplates(accountId, actorId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");

        verify(identityMapper, never()).canAccessChatAppAccount(any(), any());
        verify(chatAppTemplateService, never()).listForAccount(any());
    }

    @ParameterizedTest
    @EnumSource(value = ChatAppBroadcastModels.BroadcastStatus.class,
            names = {"SUBMITTED", "RECONCILING", "STATUS_UNKNOWN"})
    void reconciliationRequestAcceptsOnlyRecoverableStatuses(
            ChatAppBroadcastModels.BroadcastStatus status) {
        UUID broadcastId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ChatAppBroadcastEntity broadcast = existingBroadcast(broadcastId, accountId, actorId);
        broadcast.setStatus(status.name());
        broadcast.setProviderGroupMessageId("group-1");
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(accountResolver.requireOwnedAccount(actorId, accountId)).thenReturn(account(accountId));
        when(jobMapper.insertReconcileIfAbsent(any())).thenReturn(1);

        assertThat(service.requestReconciliation(broadcastId, actorId).id()).isEqualTo(broadcastId);
        verify(jobMapper).insertReconcileIfAbsent(argThat(job ->
                "RECONCILE".equals(job.getJobType()) && "PENDING".equals(job.getStatus())
                        && job.getAttemptCount() == 0 && job.getMaxAttempts() == 10
                        && NOW.equals(job.getNextAttemptAt())));
    }

    @Test
    void reconciliationRequestRejectsMissingGroupIdAndInvalidStatuses() {
        UUID broadcastId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ChatAppBroadcastEntity broadcast = existingBroadcast(broadcastId, accountId, actorId);
        broadcast.setStatus("STATUS_UNKNOWN");
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(accountResolver.requireOwnedAccount(actorId, accountId)).thenReturn(account(accountId));

        assertThatThrownBy(() -> service.requestReconciliation(broadcastId, actorId))
                .isInstanceOf(ChatAppBroadcastException.class)
                .hasMessage("CHATAPP_BROADCAST_GROUP_ID_MISSING");

        broadcast.setProviderGroupMessageId("group-1");
        for (String status : List.of("FAILED", "SUBMISSION_UNKNOWN")) {
            broadcast.setStatus(status);
            assertThatThrownBy(() -> service.requestReconciliation(broadcastId, actorId))
                    .isInstanceOfSatisfying(ChatAppBroadcastException.class,
                            error -> assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
        }
        verify(jobMapper, never()).insertReconcileIfAbsent(any());
    }

    @Test
    void activeReconciliationJobIsIdempotentAndUnauthorizedActorIsRejected() {
        UUID broadcastId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID foreignActorId = UUID.randomUUID();
        ChatAppBroadcastEntity broadcast = existingBroadcast(broadcastId, accountId, actorId);
        broadcast.setStatus("STATUS_UNKNOWN");
        broadcast.setProviderGroupMessageId("group-1");
        when(broadcastMapper.findByIdForUpdate(broadcastId)).thenReturn(Optional.of(broadcast));
        when(accountResolver.requireOwnedAccount(actorId, accountId)).thenReturn(account(accountId));
        when(jobMapper.insertReconcileIfAbsent(any())).thenReturn(0);

        assertThat(service.requestReconciliation(broadcastId, actorId).id()).isEqualTo(broadcastId);
        assertThatThrownBy(() -> service.requestReconciliation(broadcastId, foreignActorId))
                .isInstanceOfSatisfying(ChatAppBroadcastException.class,
                        error -> assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));
        verify(jobMapper).insertReconcileIfAbsent(any());
    }

    @Test
    void detailProjectsProviderAndReconciliationEvidenceWithoutExposingFullNumber() {
        UUID broadcastId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        ChatAppBroadcastEntity broadcast = existingBroadcast(broadcastId, accountId, actorId);
        broadcast.setProviderRequestId("submit-request-1");
        broadcast.setProviderCode("OK");
        broadcast.setLastReconciliationRequestId("reconcile-request-1");
        broadcast.setLastReconciliationProviderCode("InvalidParameter");
        broadcast.setProcessingCount(1);
        ChatAppBroadcastRecipientEntity recipient = new ChatAppBroadcastRecipientEntity();
        recipient.setId(UUID.randomUUID());
        recipient.setContactId(UUID.randomUUID());
        recipient.setContactIdentityId(UUID.randomUUID());
        recipient.setRecipientNameSnapshot("Recipient");
        recipient.setRecipientNumberSnapshot("60123456789");
        recipient.setTemplateParamsJsonb("{}");
        recipient.setMessageId(messageId);
        recipient.setStatus("PROCESSING");
        ChatAppBroadcastReconciliationEvidenceEntity diagnostic =
                new ChatAppBroadcastReconciliationEvidenceEntity();
        diagnostic.setDiagnosticCode("CHATAPP_PROVIDER_SUCCESS_FLAG_CONFLICT");
        when(broadcastMapper.selectById(broadcastId)).thenReturn(broadcast);
        when(accountResolver.requireOwnedAccount(actorId, accountId)).thenReturn(account(accountId));
        when(recipientMapper.findByBroadcastId(broadcastId)).thenReturn(List.of(recipient));
        when(evidenceMapper.countByBroadcastId(broadcastId)).thenReturn(3L);
        when(evidenceMapper.countMatched(broadcastId)).thenReturn(2L);
        when(evidenceMapper.countUnmatched(broadcastId)).thenReturn(1L);
        when(evidenceMapper.findLatestDiagnostic(broadcastId)).thenReturn(Optional.of(diagnostic));

        var detail = service.detail(broadcastId, actorId);

        assertThat(detail.broadcast().providerRequestId()).isEqualTo("submit-request-1");
        assertThat(detail.broadcast().lastReconciliationProviderCode()).isEqualTo("InvalidParameter");
        assertThat(detail.recipients()).singleElement().satisfies(view -> {
            assertThat(view.messageId()).isEqualTo(messageId);
            assertThat(view.maskedNumber()).endsWith("6789").doesNotContain("60123456789");
        });
        assertThat(detail.reconciliation()).isEqualTo(
                new ChatAppBroadcastModels.ReconciliationSummary(
                        3, 2, 1, 1, "CHATAPP_PROVIDER_SUCCESS_FLAG_CONFLICT"));
    }

    @Test
    void listsOnlySendableTemplatesForTheSelectedAccount() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        TemplateResponse template = new TemplateResponse(
                "shipping_notice", "Shipping Notice", "发货提醒（Shipping Notice）",
                "zh_CN", "订单 $(order) 已发货", List.of("order"), "UTILITY",
                new ObjectMapper().createArrayNode(), Map.of("order", List.of("SO-1")));
        when(accountResolver.requireOwnedAccount(actorId, accountId)).thenReturn(account(accountId));
        when(chatAppTemplateService.listForAccount(accountId)).thenReturn(List.of(template));

        assertThat(service.sendableTemplates(accountId, actorId)).containsExactly(template);
        verify(chatAppTemplateService).listForAccount(accountId);
    }

    @Test
    void rejectsTemplateListingWhenActorCannotAccessTheAccount() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(accountResolver.requireOwnedAccount(actorId, accountId))
                .thenThrow(new IllegalArgumentException("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE"));

        assertThatThrownBy(() -> service.sendableTemplates(accountId, actorId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
        verify(chatAppTemplateService, never()).listForAccount(accountId);
    }

    @Test
    void rejectsRecipientCountsOutsideOneToOneThousand() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> service.create(command(accountId, List.of()), actorId))
                .isInstanceOf(ChatAppBroadcastException.class)
                .hasMessage("CHATAPP_BROADCAST_RECIPIENT_COUNT_INVALID");

        List<ChatAppBroadcastModels.RecipientInput> tooMany = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            tooMany.add(new ChatAppBroadcastModels.RecipientInput(UUID.randomUUID(), Map.of()));
        }
        assertThatThrownBy(() -> service.create(command(accountId, tooMany), actorId))
                .isInstanceOf(ChatAppBroadcastException.class)
                .hasMessage("CHATAPP_BROADCAST_RECIPIENT_COUNT_INVALID");
        verify(broadcastMapper, never()).insertIfAbsent(any(ChatAppBroadcastEntity.class));
    }

    @Test
    void rejectsNullRecipientAsStableInvalidRequest() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        List<ChatAppBroadcastModels.RecipientInput> recipients = new ArrayList<>();
        recipients.add(null);
        var invalid = new ChatAppBroadcastModels.CreateBroadcastCommand(
                accountId, "Invalid", "shipping_notice", "zh_CN", "invalid-1",
                recipients, Map.of());

        assertThatThrownBy(() -> service.create(invalid, actorId))
                .isInstanceOf(ChatAppBroadcastException.class)
                .hasMessage("CHATAPP_BROADCAST_REQUEST_INVALID");
    }

    @Test
    void rejectsWhenAnyRecipientIsOutsideTheAccountOrUserScope() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        prepareAccountAndTemplate(accountId, actorId);
        when(identityMapper.findEligibleChatAppBroadcastRecipients(
                accountId, List.of(first, second), actorId))
                .thenReturn(List.of(candidate(first, "60111111111", "A")));

        assertThatThrownBy(() -> service.create(command(accountId, List.of(
                input(first, "name", "A"), input(second, "name", "B"))), actorId))
                .isInstanceOf(ChatAppBroadcastException.class)
                .hasMessage("CHATAPP_BROADCAST_RECIPIENT_INACCESSIBLE");
        verify(broadcastMapper, never()).insertIfAbsent(any(ChatAppBroadcastEntity.class));
    }

    @Test
    void rejectsTemplatesThatAreNotApprovedForSending() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(accountResolver.requireOwnedAccount(actorId, accountId)).thenReturn(account(accountId));
        ChannelAccountEntity account = account(accountId);
        when(accountResolver.requireOwnedAccount(actorId, accountId)).thenReturn(account);
        when(templateMapper.findSharedForSend(SCOPE_ID, "shipping_notice", "zh_CN"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(command(accountId,
                List.of(input(UUID.randomUUID(), "name", "A"))), actorId))
                .isInstanceOf(ChatAppBroadcastException.class)
                .hasMessage("CHATAPP_BROADCAST_TEMPLATE_NOT_SENDABLE");
    }

    @Test
    void rejectsSendableTemplateWithoutBodyBeforeCreatingTheBroadcast() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        TemplateEntity template = prepareAccountAndTemplate(accountId, actorId);
        template.setBody(" ");

        assertThatThrownBy(() -> service.create(command(accountId,
                List.of(input(UUID.randomUUID(), "name", "A"))), actorId))
                .isInstanceOf(ChatAppBroadcastException.class)
                .hasMessage("CHATAPP_BROADCAST_TEMPLATE_NOT_SENDABLE");

        verify(broadcastMapper, never()).insertIfAbsent(any(ChatAppBroadcastEntity.class));
    }

    @Test
    void freezesRecipientSnapshotsAndCreatesOneSubmitJob() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        TemplateEntity template = prepareAccountAndTemplate(accountId, actorId);
        when(chatAppTemplateService.requiredPlaceholders(template))
                .thenReturn(List.of("campaign", "order"));
        when(identityMapper.findEligibleChatAppBroadcastRecipients(
                accountId, List.of(identityId), actorId))
                .thenReturn(List.of(candidate(identityId, "60123456789", "CRM Name")));

        var result = service.create(command(accountId,
                List.of(input(identityId, "order", "SO-100"))), actorId);

        ArgumentCaptor<ChatAppBroadcastEntity> broadcast =
                ArgumentCaptor.forClass(ChatAppBroadcastEntity.class);
        ArgumentCaptor<ChatAppBroadcastRecipientEntity> recipient =
                ArgumentCaptor.forClass(ChatAppBroadcastRecipientEntity.class);
        ArgumentCaptor<ChatAppBroadcastJobEntity> job =
                ArgumentCaptor.forClass(ChatAppBroadcastJobEntity.class);
        verify(broadcastMapper).insertIfAbsent(broadcast.capture());
        verify(recipientMapper).insert(recipient.capture());
        verify(jobMapper).insert(job.capture());

        assertThat(result.status()).isEqualTo(QUEUED);
        assertThat(broadcast.getValue().getRecipientCount()).isEqualTo(1);
        assertThat(broadcast.getValue().getProcessingCount()).isEqualTo(1);
        assertThat(broadcast.getValue().getTemplateBodySnapshot()).isEqualTo("Shipping notice");
        assertThat(recipient.getValue().getRecipientNameSnapshot()).isEqualTo("CRM Name");
        assertThat(recipient.getValue().getRecipientNumberSnapshot()).isEqualTo("60123456789");
        assertThat(recipient.getValue().getTemplateParamsJsonb())
                .isEqualTo("{\"campaign\":\"August\",\"order\":\"SO-100\"}");
        assertThat(job.getValue().getJobType()).isEqualTo("SUBMIT");
        assertThat(job.getValue().getMaxAttempts()).isEqualTo(1);
    }

    @Test
    void sameIdempotencyFingerprintReturnsExistingAndDifferentFingerprintConflicts() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        var command = command(accountId, List.of(input(identityId, "name", "A")));
        String fingerprint = ChatAppBroadcastApplicationService.fingerprint(command, new ObjectMapper());
        ChatAppBroadcastEntity existing = new ChatAppBroadcastEntity();
        existing.setId(UUID.randomUUID());
        existing.setChannelAccountId(accountId);
        existing.setName("August notice");
        existing.setTemplateCode("shipping_notice");
        existing.setTemplateName("Shipping Notice");
        existing.setLanguageCode("zh_CN");
        existing.setRecipientCount(1);
        existing.setSuccessCount(0);
        existing.setFailedCount(0);
        existing.setProcessingCount(1);
        existing.setStatus("QUEUED");
        existing.setClientRequestId("request-1");
        existing.setRequestFingerprint(fingerprint);
        existing.setCreatedByUserId(actorId);
        existing.setCreatedAt(NOW);
        existing.setUpdatedAt(NOW);
        when(broadcastMapper.findByIdempotency(accountId, "request-1"))
                .thenReturn(Optional.of(existing));

        assertThat(service.create(command, actorId).id()).isEqualTo(existing.getId());

        var changed = new ChatAppBroadcastModels.CreateBroadcastCommand(
                accountId, "Changed", "shipping_notice", "zh_CN", "request-1",
                List.of(input(identityId, "name", "A")), null);
        assertThatThrownBy(() -> service.create(changed, actorId))
                .isInstanceOf(ChatAppBroadcastException.class)
                .hasMessage("CHATAPP_BROADCAST_IDEMPOTENCY_CONFLICT");
        verify(accountResolver, times(2)).requireOwnedAccount(actorId, accountId);
    }

    @Test
    void validatesRequiredTemplateVariablesForEveryRecipient() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        TemplateEntity template = prepareAccountAndTemplate(accountId, actorId);
        when(chatAppTemplateService.requiredPlaceholders(template)).thenReturn(List.of("order"));
        when(identityMapper.findEligibleChatAppBroadcastRecipients(
                accountId, List.of(identityId), actorId))
                .thenReturn(List.of(candidate(identityId, "60123456789", "CRM Name")));

        var missing = new ChatAppBroadcastModels.CreateBroadcastCommand(
                accountId, "Missing", "shipping_notice", "zh_CN", "missing-1",
                List.of(new ChatAppBroadcastModels.RecipientInput(identityId, Map.of())), Map.of());
        assertThatThrownBy(() -> service.create(missing, actorId))
                .isInstanceOf(ChatAppBroadcastException.class)
                .hasMessage("CHATAPP_BROADCAST_VARIABLES_INVALID");

        var extra = new ChatAppBroadcastModels.CreateBroadcastCommand(
                accountId, "Extra", "shipping_notice", "zh_CN", "extra-1",
                List.of(new ChatAppBroadcastModels.RecipientInput(
                        identityId, Map.of("order", "SO-1", "unexpected", "value"))), Map.of());
        assertThatThrownBy(() -> service.create(extra, actorId))
                .isInstanceOf(ChatAppBroadcastException.class)
                .hasMessage("CHATAPP_BROADCAST_VARIABLES_INVALID");

        var oversized = new ChatAppBroadcastModels.CreateBroadcastCommand(
                accountId, "Oversized", "shipping_notice", "zh_CN", "oversized-1",
                List.of(new ChatAppBroadcastModels.RecipientInput(
                        identityId, Map.of("order", "x".repeat(1025)))), Map.of());
        assertThatThrownBy(() -> service.create(oversized, actorId))
                .isInstanceOf(ChatAppBroadcastException.class)
                .hasMessage("CHATAPP_BROADCAST_VARIABLES_INVALID");

        verify(broadcastMapper, never()).insertIfAbsent(any(ChatAppBroadcastEntity.class));
    }

    @Test
    void lostIdempotencyInsertRaceReturnsTheCommittedExistingBroadcast() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        var command = command(accountId, List.of(input(identityId, "name", "A")));
        String fingerprint = ChatAppBroadcastApplicationService.fingerprint(command, new ObjectMapper());
        ChatAppBroadcastEntity existing = existingBroadcast(UUID.randomUUID(), accountId, actorId);
        existing.setClientRequestId(command.clientRequestId());
        existing.setRequestFingerprint(fingerprint);
        when(broadcastMapper.findByIdempotency(accountId, command.clientRequestId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(broadcastMapper.insertIfAbsent(any(ChatAppBroadcastEntity.class))).thenReturn(0);
        TemplateEntity template = prepareAccountAndTemplate(accountId, actorId);
        when(chatAppTemplateService.requiredPlaceholders(template))
                .thenReturn(List.of("campaign", "name"));
        when(identityMapper.findEligibleChatAppBroadcastRecipients(
                accountId, List.of(identityId), actorId))
                .thenReturn(List.of(candidate(identityId, "60123456789", "CRM Name")));

        assertThat(service.create(command, actorId).id()).isEqualTo(existing.getId());

        verify(recipientMapper, never()).insert(any(ChatAppBroadcastRecipientEntity.class));
        verify(jobMapper, never()).insert(any(ChatAppBroadcastJobEntity.class));
    }

    @Test
    void nonOwnerCannotReadBroadcastDetailsOrFailureData() {
        UUID accountId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID broadcastId = UUID.randomUUID();
        ChatAppBroadcastEntity existing = existingBroadcast(broadcastId, accountId, ownerId);
        when(broadcastMapper.selectById(broadcastId)).thenReturn(existing);

        assertThatThrownBy(() -> service.detail(broadcastId, actorId))
                .isInstanceOf(ChatAppBroadcastException.class)
                .hasMessage("CHATAPP_BROADCAST_FORBIDDEN");
        assertThatThrownBy(() -> service.failures(broadcastId, 1, 20, actorId))
                .isInstanceOf(ChatAppBroadcastException.class)
                .hasMessage("CHATAPP_BROADCAST_FORBIDDEN");

        verify(recipientMapper, never()).findByBroadcastId(broadcastId);
        verify(recipientMapper, never()).findFailures(broadcastId, 20, 0);
    }

    @Test
    void nonAdminListingIsScopedToBroadcastsCreatedByTheActor() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ChannelAccountEntity account = account(accountId);
        when(accountResolver.requireOwnedAccount(actorId, accountId)).thenReturn(account);
        when(broadcastMapper.selectCount(any())).thenReturn(0L);

        service.list(accountId, 1, 20, actorId);

        verify(broadcastMapper).selectCount(argThat(query ->
                query.getSqlSegment().contains("created_by_user_id")));
    }

    @Test
    void retryFailuresCreatesANewLinkedBroadcastFromOnlyFailedSnapshots() {
        UUID accountId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID originalId = UUID.randomUUID();
        UUID failedIdentityId = UUID.randomUUID();
        ChatAppBroadcastEntity original = existingBroadcast(originalId, accountId, actorId);
        original.setStatus("PARTIALLY_FAILED");
        original.setFailedCount(1);
        original.setSuccessCount(1);
        original.setProcessingCount(0);
        ChatAppBroadcastRecipientEntity failed = new ChatAppBroadcastRecipientEntity();
        failed.setId(UUID.randomUUID());
        failed.setBroadcastId(originalId);
        failed.setContactId(UUID.randomUUID());
        failed.setContactIdentityId(failedIdentityId);
        failed.setRecipientNameSnapshot("Old snapshot");
        failed.setRecipientNumberSnapshot("60123456789");
        failed.setTemplateParamsJsonb("{\"order\":\"SO-100\"}");
        failed.setStatus("FAILED_RECIPIENT");
        when(broadcastMapper.findByIdForUpdate(originalId)).thenReturn(Optional.of(original));
        when(recipientMapper.findFailures(originalId, 1000, 0)).thenReturn(List.of(failed));
        when(broadcastMapper.findByIdempotency(accountId, "retry-1")).thenReturn(Optional.empty());
        TemplateEntity template = prepareAccountAndTemplate(accountId, actorId);
        when(chatAppTemplateService.requiredPlaceholders(template)).thenReturn(List.of("order"));
        when(identityMapper.findEligibleChatAppBroadcastRecipients(
                accountId, List.of(failedIdentityId), actorId))
                .thenReturn(List.of(candidate(failedIdentityId, "60123456789", "Current CRM Name")));

        var result = service.retryFailures(originalId, "Retry failed", "retry-1", actorId);

        ArgumentCaptor<ChatAppBroadcastEntity> newBroadcast =
                ArgumentCaptor.forClass(ChatAppBroadcastEntity.class);
        verify(broadcastMapper).insertIfAbsent(newBroadcast.capture());
        assertThat(result.recipientCount()).isEqualTo(1);
        assertThat(newBroadcast.getValue().getRetriesBroadcastId()).isEqualTo(originalId);
        assertThat(newBroadcast.getValue().getName()).isEqualTo("Retry failed");
    }

    private static ChatAppBroadcastEntity existingBroadcast(
            UUID id, UUID accountId, UUID actorId) {
        ChatAppBroadcastEntity existing = new ChatAppBroadcastEntity();
        existing.setId(id);
        existing.setChannelAccountId(accountId);
        existing.setName("August notice");
        existing.setTemplateCode("shipping_notice");
        existing.setTemplateName("Shipping Notice");
        existing.setLanguageCode("zh_CN");
        existing.setRecipientCount(2);
        existing.setSuccessCount(0);
        existing.setFailedCount(0);
        existing.setProcessingCount(2);
        existing.setStatus("QUEUED");
        existing.setClientRequestId("request-original");
        existing.setRequestFingerprint("a".repeat(64));
        existing.setCreatedByUserId(actorId);
        existing.setCreatedAt(NOW);
        existing.setUpdatedAt(NOW);
        return existing;
    }

    private TemplateEntity prepareAccountAndTemplate(UUID accountId, UUID actorId) {
        when(accountResolver.requireOwnedAccount(actorId, accountId)).thenReturn(account(accountId));
        TemplateEntity template = new TemplateEntity();
        template.setChannelAccountId(accountId);
        template.setProviderTemplateId("shipping_notice");
        template.setName("Shipping Notice");
        template.setLanguageCode("zh_CN");
        template.setStatus("APPROVED");
        template.setAllowSend(true);
        template.setBody("Shipping notice");
        when(templateMapper.findSharedForSend(SCOPE_ID, "shipping_notice", "zh_CN"))
                .thenReturn(Optional.of(template));
        return template;
    }

    private static ChannelAccountEntity account(UUID id) {
        ChannelAccountEntity account = new ChannelAccountEntity();
        account.setId(id);
        account.setChannelType("chatapp");
        account.setAuthStatus("active");
        account.setAccountIdentifier("60199999999");
        account.setProviderScopeId(SCOPE_ID);
        return account;
    }

    private static ChatAppBroadcastModels.RecipientCandidate candidate(
            UUID identityId, String number, String name) {
        return new ChatAppBroadcastModels.RecipientCandidate(
                identityId, UUID.randomUUID(), number, number, name);
    }

    private static ChatAppBroadcastModels.RecipientInput input(
            UUID identityId, String key, String value) {
        return new ChatAppBroadcastModels.RecipientInput(identityId, Map.of(key, value));
    }

    private static ChatAppBroadcastModels.CreateBroadcastCommand command(
            UUID accountId, List<ChatAppBroadcastModels.RecipientInput> recipients) {
        Map<String, String> shared = new LinkedHashMap<>();
        shared.put("campaign", "August");
        return new ChatAppBroadcastModels.CreateBroadcastCommand(
                accountId, "August notice", "shipping_notice", "zh_CN", "request-1",
                recipients, shared);
    }
}
