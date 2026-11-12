package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.App;
import com.crmforlogistics.messagecenter.entity.ContactMemoryAttemptEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryStateEntity;
import com.crmforlogistics.messagecenter.entity.ContactProfileVersionEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecentertest.ApplicationIntegrationTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = {App.class, ApplicationIntegrationTestConfiguration.class},
        properties = {
                "spring.profiles.active=test",
                "app.chatapp-sync-enabled=false",
                "app.chatapp-outbox-enabled=false",
                "app.chatapp-webhook-worker-enabled=false",
                "app.chatapp-broadcast-worker-enabled=false",
                "app.chatapp-template-reconcile-enabled=false",
                "app.email-sync-enabled=false",
                "contact-memory.time-zone=UTC"
        }
)
@Testcontainers(disabledWithoutDocker = true)
class ContactMemoryEndToEndTest {

    private static final Instant FIRST_MESSAGE_AT = Instant.parse("2026-09-13T01:00:00Z");
    private static final Instant SECOND_MESSAGE_AT = Instant.parse("2026-09-13T02:00:00Z");
    private static final Instant THIRD_MESSAGE_AT = Instant.parse("2026-09-13T03:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("minio.endpoint", () -> "http://localhost:9999");
        registry.add("minio.access-key", () -> "test");
        registry.add("minio.secret-key", () -> "test");
        registry.add("minio.bucket", () -> "test");
        registry.add("credential.master-key", () ->
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MessageMapper messages;

    @Autowired
    private ContactMemoryStateMapper states;

    @Autowired
    private ContactMemoryMapper memory;

    @Autowired
    private ContactMemoryTriggerService trigger;

    @Autowired
    private ContactMemoryContextService contextService;

    @Autowired
    private ContactMemoryWorker worker;

    @Autowired
    private ContactMemoryQueryService query;

    @Autowired
    private ContactMemoryRecomputeService recompute;

    @MockitoBean
    private ContactMemoryLlmGateway gateway;

    @BeforeEach
    void resetGateway() {
        reset(gateway);
    }

    @Test
    void inboundMessagesProduceProfileFactsAndAiLabelsWithoutChangingManualTags() {
        TestFixture fixture = fixture();
        UUID manualTagId = insertManualTag(fixture);
        MessageEntity first = insertInboundMessage(fixture, FIRST_MESSAGE_AT, "客户明确关注海运方案。");
        MessageEntity second = insertInboundMessage(fixture, SECOND_MESSAGE_AT, "客户希望了解海运报价和时效。");

        trigger.markInboundPersisted(fixture.contactId(), second.getId(), second.getIngestSequence(),
                second.getOccurredAt(), second.getReceivedAt());
        // A trigger event's next_attempt_at comes from the database clock, so the replay window
        // and the worker run have to come from the real clock too, not the simulated message timeline.
        Instant processingNow = Instant.now();
        assertThat(trigger.replayDue(processingNow, 10)).isEqualTo(1);
        ContactMemoryModels.Context context = contextFor(fixture, SECOND_MESSAGE_AT);
        when(gateway.generate(any())).thenReturn(outputFor(context, first, second));

        int processed = worker.runOnce(processingNow);

        assertThat(processed).isEqualTo(1);
        assertThat(state(fixture)).satisfies(state -> {
            assertThat(state.getStatus()).isEqualTo("CLEAN");
            assertThat(state.getLastSuccessCursor())
                    .isEqualTo(receivedCursor(second));
        });

        ContactProfileVersionEntity profile = memory.findCurrentProfile(
                fixture.ownerId(), fixture.contactId());
        assertThat(profile).isNotNull();
        assertThat(profile.getContent()).isEqualTo("客户关注海运，重视报价与时效。");
        assertThat(profile.getVersion()).isEqualTo(1L);

        ContactMemoryFactEntity fact = memory.findActiveFactForLabel(
                fixture.ownerId(), fixture.contactId(), "PRODUCT_INTEREST", "海运");
        assertThat(fact).isNotNull();
        assertThat(fact.getEvidenceCount()).isEqualTo(2);

        Map<String, Object> aiLabel = jdbc.queryForMap("""
                select display_name, category, color_token, status
                from contact_ai_labels
                where contact_id = ? and owner_user_id = ?
                """, fixture.contactId(), fixture.ownerId());
        assertThat(aiLabel)
                .containsEntry("display_name", "海运")
                .containsEntry("category", "PRODUCT_INTEREST")
                .containsEntry("color_token", "green")
                .containsEntry("status", "ACTIVE");

        assertThat(jdbc.queryForObject(
                "select count(*) from contact_memory_observations where contact_id = ? and owner_user_id = ?",
                Integer.class, fixture.contactId(), fixture.ownerId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from contact_memory_fact_evidence where contact_id = ? and owner_user_id = ?",
                Integer.class, fixture.contactId(), fixture.ownerId())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from contact_ai_label_evidence where contact_id = ? and owner_user_id = ?",
                Integer.class, fixture.contactId(), fixture.ownerId())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from contact_memory_attempts where contact_id = ? and owner_user_id = ? and status = 'SUCCEEDED'",
                Integer.class, fixture.contactId(), fixture.ownerId())).isEqualTo(1);

        ContactMemoryResponseSnapshot ownerView =
                ContactMemoryResponseSnapshot.from(query.findForOwner(
                        fixture.ownerId(), fixture.contactId()).orElseThrow());
        assertThat(ownerView.manualTagNames()).containsExactly("人工重要");
        assertThat(ownerView.aiTagNames()).containsExactly("海运");
        assertThat(ownerView.profile()).isEqualTo("客户关注海运，重视报价与时效。");
        assertThat(jdbc.queryForObject(
                "select count(*) from contact_taggings where contact_id = ? and tag_id = ?",
                Integer.class, fixture.contactId(), manualTagId)).isEqualTo(1);

        assertThat(query.findForOwner(fixture.otherOwnerId(), fixture.contactId())).isEmpty();
        verify(gateway).generate(any(ContactMemoryModels.Context.class));
    }

    @Test
    void llmFailureLeavesPreviousProjectionAndCursorUntouched() {
        TestFixture fixture = fixture();
        MessageEntity first = insertInboundMessage(fixture, FIRST_MESSAGE_AT, "客户关注海运。");
        MessageEntity second = insertInboundMessage(fixture, SECOND_MESSAGE_AT, "客户需要海运报价。");
        trigger.markInboundPersisted(fixture.contactId(), second.getId(), second.getIngestSequence(),
                second.getOccurredAt(), second.getReceivedAt());
        Instant processingNow = Instant.now();
        assertThat(trigger.replayDue(processingNow, 10)).isEqualTo(1);

        ContactMemoryModels.Context firstContext = contextFor(fixture, SECOND_MESSAGE_AT);
        when(gateway.generate(any())).thenReturn(outputFor(firstContext, first, second));
        worker.runOnce(processingNow);

        ContactProfileVersionEntity oldProfile = memory.findCurrentProfile(
                fixture.ownerId(), fixture.contactId());
        String oldCursor = state(fixture).getLastSuccessCursor();
        int oldFactCount = jdbc.queryForObject(
                "select count(*) from contact_memory_facts where contact_id = ? and owner_user_id = ?",
                Integer.class, fixture.contactId(), fixture.ownerId());
        int oldLabelCount = jdbc.queryForObject(
                "select count(*) from contact_ai_labels where contact_id = ? and owner_user_id = ?",
                Integer.class, fixture.contactId(), fixture.ownerId());
        int oldAttemptCount = jdbc.queryForObject(
                "select count(*) from contact_memory_attempts where contact_id = ? and owner_user_id = ?",
                Integer.class, fixture.contactId(), fixture.ownerId());

        MessageEntity third = insertInboundMessage(fixture, THIRD_MESSAGE_AT, "客户补充需要稳定船期。");
        trigger.markInboundPersisted(fixture.contactId(), third.getId(), third.getIngestSequence(),
                third.getOccurredAt(), third.getReceivedAt());
        Instant retryNow = Instant.now();
        assertThat(trigger.replayDue(retryNow, 10)).isEqualTo(1);
        doThrow(new ContactMemoryLlmGateway.GatewayException("LLM_TIMEOUT", true))
                .when(gateway).generate(any());

        worker.runOnce(retryNow);

        ContactMemoryStateEntity failed = state(fixture);
        assertThat(failed.getStatus()).isEqualTo("RETRY_WAIT");
        assertThat(failed.getLastFailureCode()).isEqualTo("LLM_TIMEOUT");
        assertThat(failed.getLastSuccessCursor()).isEqualTo(oldCursor);
        assertThat(memory.findCurrentProfile(fixture.ownerId(), fixture.contactId()).getId())
                .isEqualTo(oldProfile.getId());
        assertThat(jdbc.queryForObject(
                "select count(*) from contact_memory_facts where contact_id = ? and owner_user_id = ?",
                Integer.class, fixture.contactId(), fixture.ownerId())).isEqualTo(oldFactCount);
        assertThat(jdbc.queryForObject(
                "select count(*) from contact_ai_labels where contact_id = ? and owner_user_id = ?",
                Integer.class, fixture.contactId(), fixture.ownerId())).isEqualTo(oldLabelCount);
        assertThat(jdbc.queryForObject(
                "select count(*) from contact_memory_attempts where contact_id = ? and owner_user_id = ?",
                Integer.class, fixture.contactId(), fixture.ownerId())).isEqualTo(oldAttemptCount + 1);
        assertThat(jdbc.queryForObject(
                "select count(*) from contact_memory_attempts where contact_id = ? and owner_user_id = ? and status = 'FAILED'",
                Integer.class, fixture.contactId(), fixture.ownerId())).isEqualTo(1);
        assertThat(third.getId()).isNotNull();
    }

    @Test
    void unsummarizedInboundMessagesAreDiscoveredWithoutATriggerEvent() {
        TestFixture fixture = fixture();
        MessageEntity first = insertInboundMessage(fixture, FIRST_MESSAGE_AT, "客户关注海运方案。");
        MessageEntity second = insertInboundMessage(fixture, SECOND_MESSAGE_AT, "客户希望了解海运报价和时效。");
        // No markInboundPersisted here: the messages exist and the contact has no memory state,
        // which is exactly the backlog the nightly run has to pick up on its own.
        // contacts.owner_user_id is unset on real rows, so the discovery has to key off
        // contacts.created_by, the identity the context service validates the owner against.
        jdbc.update("update contacts set owner_user_id = null where id = ?", fixture.contactId());
        Instant processingNow = Instant.now();
        ContactMemoryModels.Context context = contextFor(fixture, processingNow);
        when(gateway.generate(any())).thenReturn(outputFor(context, first, second));

        int processed = worker.runOnce(processingNow);

        assertThat(processed).isEqualTo(1);
        ContactMemoryStateEntity caughtUp = state(fixture);
        assertThat(caughtUp.getStatus()).isEqualTo("CLEAN");
        assertThat(caughtUp.getLastSuccessCursor()).isEqualTo(receivedCursor(second));
        assertThat(memory.findCurrentProfile(fixture.ownerId(), fixture.contactId())).isNotNull();
        verify(gateway).generate(any(ContactMemoryModels.Context.class));

        // A second run must find nothing left to do for this contact rather than recomputing it.
        worker.runOnce(Instant.now());

        assertThat(state(fixture).getLastSuccessCursor()).isEqualTo(receivedCursor(second));
        assertThat(jdbc.queryForObject(
                "select count(*) from contact_memory_attempts where contact_id = ? and owner_user_id = ?",
                Integer.class, fixture.contactId(), fixture.ownerId())).isEqualTo(1);
    }

    @Test
    void lateInboundMessageBehindSuccessCursorIsStillSentToTheModel() {
        TestFixture fixture = fixture();
        MessageEntity latestProcessed = insertInboundMessage(
                fixture, FIRST_MESSAGE_AT, "已处理的入站消息。");

        ContactMemoryStateEntity state = new ContactMemoryStateEntity();
        state.setId(UUID.randomUUID());
        state.setContactId(fixture.contactId());
        state.setOwnerUserId(fixture.ownerId());
        state.setStatus("CLEAN");
        state.setLastInboundAt(latestProcessed.getReceivedAt());
        state.setLastSuccessCursor(receivedCursor(latestProcessed));
        state.setRetryCount(0);
        assertThat(states.insert(state)).isEqualTo(1);

        MessageEntity late = insertInboundMessage(
                fixture, SECOND_MESSAGE_AT, "迟到入库但应参与画像的旧消息。");
        Instant lateReceivedAt = latestProcessed.getReceivedAt().minusSeconds(60);
        jdbc.update("update messages set received_at = ? where id = ?",
                Timestamp.from(lateReceivedAt), late.getId());
        late.setReceivedAt(lateReceivedAt);
        trigger.markInboundPersisted(fixture.contactId(), late.getId(), late.getIngestSequence(),
                late.getOccurredAt(), lateReceivedAt);

        Instant processingNow = Instant.now();
        assertThat(trigger.replayDue(processingNow, 10)).isEqualTo(1);
        when(gateway.generate(any())).thenAnswer(invocation ->
                new ContactMemoryModels.LlmOutput(
                        List.of(), null, List.of(), List.of(), "test-model", "{}"));

        worker.runOnce(processingNow);

        ArgumentCaptor<ContactMemoryModels.Context> context =
                ArgumentCaptor.forClass(ContactMemoryModels.Context.class);
        verify(gateway).generate(context.capture());
        assertThat(context.getValue().inboundMessages())
                .extracting(MessageEntity::getId)
                .containsExactly(late.getId());
    }

    /**
     * 手动重算（{@code contact.refresh_memory} 底下那一步）在真库上的行为。
     *
     * <p>为什么这条要在真库上验：判据全在 SQL 里（与自动路径 {@code markStaleDirty} 同一条），
     * 而它写错时的症状是<b>静默死循环</b>而不是断言失败 —— 把 {@code now} 写进
     * {@code last_inbound_at}，{@code complete()} 就会在每轮跑完后立刻把状态改回 {@code DIRTY}。
     * 所以这里验三件事：写进去的是消息表的真时间、没有新内容时真的 0 行、别人名下连读都不读。
     */
    @Test
    void manualRecomputeMarksDirtyOnlyWhileSomethingIsStillWaitingToBeProcessed() {
        TestFixture fixture = fixture();
        MessageEntity only = insertInboundMessage(fixture, FIRST_MESSAGE_AT, "客户关注海运。");

        // 1) 从未处理过 + 有一条入站消息 ⇒ 标脏，且 last_inbound_at 必须是那条消息的真实入库时间
        assertThat(recompute.requestRecompute(fixture.ownerId(), fixture.contactId()))
                .contains(ContactMemoryRecomputeService.Outcome.SUBMITTED);
        ContactMemoryStateEntity submitted = state(fixture);
        assertThat(submitted.getStatus()).isEqualTo("DIRTY");
        assertThat(submitted.getLastInboundAt())
                .as("必须是 messages.received_at，不能是「现在」——写成现在会让每一轮跑完立刻又变脏")
                .isEqualTo(only.getReceivedAt());

        // 2) 已经在队列里 ⇒ 不重复提交（也就不会把用户以为「上一次没提交成功」）
        assertThat(recompute.requestRecompute(fixture.ownerId(), fixture.contactId()))
                .contains(ContactMemoryRecomputeService.Outcome.ALREADY_PENDING);

        // 3) 游标追上最后一条入站消息 ⇒ 没有新内容：一个字节都不写，状态与时间都保持原样
        jdbc.update("update contact_memory_states set status = 'CLEAN', last_success_cursor = ? "
                        + "where contact_id = ? and owner_user_id = ?",
                receivedCursor(only), fixture.contactId(), fixture.ownerId());
        assertThat(recompute.requestRecompute(fixture.ownerId(), fixture.contactId()))
                .as("说成「已提交」会让用户等一个永远不会发生的变化")
                .contains(ContactMemoryRecomputeService.Outcome.NOTHING_NEW);
        ContactMemoryStateEntity untouched = state(fixture);
        assertThat(untouched.getStatus()).isEqualTo("CLEAN");
        assertThat(untouched.getLastInboundAt()).isEqualTo(only.getReceivedAt());

        // 4) 记忆不归他 ⇒ 空（连状态表都不去读）
        assertThat(recompute.requestRecompute(fixture.otherOwnerId(), fixture.contactId())).isEmpty();
    }

    private ContactMemoryModels.Context contextFor(TestFixture fixture, Instant cutoff) {
        return contextService.load(fixture.ownerId(), fixture.contactId(), cutoff);
    }

    private ContactMemoryModels.LlmOutput outputFor(ContactMemoryModels.Context context,
                                                    MessageEntity first,
                                                    MessageEntity second) {
        List<ContactMemoryModels.EvidenceRef> evidence = List.of(
                new ContactMemoryModels.EvidenceRef(ContactMemoryModels.EvidenceType.MESSAGE, first.getId()),
                new ContactMemoryModels.EvidenceRef(ContactMemoryModels.EvidenceType.MESSAGE, second.getId()));
        return new ContactMemoryModels.LlmOutput(
                List.of(new ContactMemoryModels.ObservationCandidate(
                        ContactMemoryModels.Category.PRODUCT_INTEREST,
                        "product_interest",
                        "海运",
                        ContactMemoryModels.Polarity.POSITIVE,
                        new BigDecimal("0.90"),
                        evidence,
                        "两条入站消息均明确表达海运兴趣")),
                new ContactMemoryModels.ProfileCandidate("客户关注海运，重视报价与时效。"),
                List.of(new ContactMemoryModels.LabelChange(
                        ContactMemoryModels.LabelOperation.ADD,
                        ContactMemoryModels.Category.PRODUCT_INTEREST,
                        "海运",
                        new BigDecimal("0.90"),
                        evidence,
                        "客户持续关注海运方案")),
                List.of(),
                "test-contact-memory-model",
                "{}");
    }

    private MessageEntity insertInboundMessage(TestFixture fixture, Instant occurredAt, String body) {
        MessageEntity message = new MessageEntity();
        message.setId(UUID.randomUUID());
        message.setConversationId(fixture.conversationId());
        message.setChannelAccountId(fixture.channelAccountId());
        message.setProviderMessageId("provider-" + message.getId());
        message.setDirection("inbound");
        message.setMessageKind("text");
        message.setBodyText(body);
        message.setOccurredAt(occurredAt);
        message.setReceivedAt(occurredAt.plusSeconds(1));
        message.setCountsAsUnread(true);
        message.setCurrentStatus("delivered");
        message.setCurrentStatusAt(occurredAt.plusSeconds(1));
        message.setMetadataJsonb("{}");
        assertThat(messages.insertWithSequence(message)).isEqualTo(1);
        // received_at is stamped by the database clock rather than by the caller (the insert does
        // not persist the entity's value), so the cursor the worker computes uses that timestamp
        // and the expectation has to read it back instead of assuming the simulated timeline.
        message.setReceivedAt(jdbc.queryForObject(
                "select received_at from messages where id = ?",
                Timestamp.class, message.getId()).toInstant());
        return message;
    }

    private ContactMemoryStateEntity state(TestFixture fixture) {
        return states.findByOwnerAndContact(fixture.ownerId(), fixture.contactId()).orElseThrow();
    }

    private UUID insertManualTag(TestFixture fixture) {
        UUID tagId = UUID.randomUUID();
        jdbc.update("""
                insert into contact_tags (id, owner_user_id, name, color, status)
                values (?, ?, '人工重要', 'red', 'active')
                """, tagId, fixture.ownerId());
        jdbc.update("""
                insert into contact_taggings (contact_id, tag_id)
                values (?, ?)
                """, fixture.contactId(), tagId);
        return tagId;
    }

    private TestFixture fixture() {
        UUID ownerId = UUID.randomUUID();
        UUID otherOwnerId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        UUID channelAccountId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-13T00:00:00Z");

        insertUser(ownerId, "memory-owner-" + ownerId);
        insertUser(otherOwnerId, "memory-other-" + otherOwnerId);
        jdbc.update("""
                insert into contacts
                    (id, display_name, status, created_by, owner_user_id, created_at, updated_at)
                values (?, 'Memory Contact', 'active', ?, ?, ?, ?)
                """, contactId, ownerId, ownerId, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into channel_accounts
                    (id, owner_user_id, channel_type, name, account_identifier,
                     account_identifier_normalized, auth_status, encrypted_config)
                values (?, ?, 'email', 'Memory Email', ?, ?, 'active', '{}'::jsonb)
                """, channelAccountId, ownerId, "memory-" + channelAccountId,
                "memory-" + channelAccountId);
        jdbc.update("""
                insert into contact_identities
                    (id, contact_id, channel_type, identity_scope, identity_value,
                     normalized_value, display_name, is_primary, verify_status, source)
                values (?, ?, 'email', ?, 'customer@example.com', 'customer@example.com',
                        'Memory Customer', true, 'verified', 'manual')
                """, identityId, contactId, ownerId.toString());
        jdbc.update("""
                insert into conversations
                    (id, channel_account_id, contact_identity_id, status, next_ingest_sequence)
                values (?, ?, ?, 'open', 0)
                """, conversationId, channelAccountId, identityId);
        return new TestFixture(ownerId, otherOwnerId, contactId, channelAccountId, conversationId);
    }

    private void insertUser(UUID id, String username) {
        jdbc.update("""
                insert into users
                    (id, username, username_normalized, password_hash, display_name, status)
                values (?, ?, ?, 'test-hash', ?, 'active')
                """, id, username, username, username);
    }

    private static String receivedCursor(MessageEntity message) {
        return message.getReceivedAt() + "|" + message.getId();
    }

    private record TestFixture(UUID ownerId,
                               UUID otherOwnerId,
                               UUID contactId,
                               UUID channelAccountId,
                               UUID conversationId) {
    }

    private record ContactMemoryResponseSnapshot(List<String> manualTagNames,
                                                 List<String> aiTagNames,
                                                 String profile) {
        static ContactMemoryResponseSnapshot from(
                com.crmforlogistics.messagecenter.dto.response.ContactMemoryResponse response) {
            return new ContactMemoryResponseSnapshot(
                    response.humanTags().stream().map(
                            com.crmforlogistics.messagecenter.dto.response.ContactTagResponse::name).toList(),
                    response.aiTags().stream()
                            .map(com.crmforlogistics.messagecenter.dto.response.ContactMemoryResponse.AiTag::name)
                            .toList(),
                    response.profile() == null ? null : response.profile().content());
        }
    }
}
