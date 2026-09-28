package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.AssistantConfig;
import com.crmforlogistics.messagecenter.entity.AssistantPendingActionEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import com.crmforlogistics.messagecenter.mapper.AssistantPendingActionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.TodoItemMapper;
import com.crmforlogistics.messagecenter.service.assistant.mcp.MessageSendAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolDefinition;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolExecutionException;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolInputValidator;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolRegistry;
import com.crmforlogistics.messagecenter.service.channel.OutboundMessageService;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 待确认动作的生命周期：确认、取消、过期、越权，以及「确认时重新校验」。
 *
 * <p>时序用注入的 {@code Clock} 精确构造，不靠 {@code Thread.sleep} ——
 * 过期这件事必须能被稳定地测出来，而不是碰运气。
 */
class AssistantPendingActionServiceTest {

    private static final AssistantConfig CONFIG = AssistantFixtures.config();
    private static final Instant NOW = Instant.parse("2026-09-21T02:00:00Z");
    private static final UUID PENDING_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");

    private final AssistantPendingActionMapper pendingMapper = mock(AssistantPendingActionMapper.class);
    private final TodoItemMapper todoMapper = mock(TodoItemMapper.class);
    private final AssistantAuditService audit = mock(AssistantAuditService.class);
    private final ContactMapper contacts = mock(ContactMapper.class);

    private final ToolRegistry registry = AssistantFixtures.registry(new TodoItemService(todoMapper));
    private final AssistantDecisionParser parser =
            new AssistantDecisionParser(registry, new ToolInputValidator(), AssistantFixtures.objectMapper());

    private AssistantPendingActionService service;

    @BeforeEach
    void setUp() {
        service = new AssistantPendingActionService(
                pendingMapper, parser, registry, audit, CONFIG,
                AssistantFixtures.objectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                mock(OutboundMessageService.class), contacts);
    }

    // ---------- 确认 ----------

    @Test
    void confirmingExecutesTheAction() {
        when(pendingMapper.findById(PENDING_ID, AssistantFixtures.USER))
                .thenReturn(pending("todo.complete", Map.of("todoId", AssistantFixtures.TODO_QUOTE, "completed", true), NOW.plusSeconds(600)));
        when(pendingMapper.markDecided(PENDING_ID, AssistantFixtures.USER, "CONFIRMED")).thenReturn(1);
        when(todoMapper.findById(UUID.fromString(AssistantFixtures.TODO_QUOTE), AssistantFixtures.USER))
                .thenReturn(todo());
        when(todoMapper.setCompleted(any(), any(), anyBoolean())).thenReturn(1);

        AssistantTurnResult result = service.confirm(AssistantFixtures.USER, PENDING_ID);

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.EXECUTED);
        assertThat(result.message()).contains("已标记完成").contains("和张总确认报价");
        verify(todoMapper).setCompleted(UUID.fromString(AssistantFixtures.TODO_QUOTE), AssistantFixtures.USER, true);

        ArgumentCaptor<AssistantAuditService.Entry> entry =
                ArgumentCaptor.forClass(AssistantAuditService.Entry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().policy()).isEqualTo("CONFIRMED");
        assertThat(entry.getValue().outcome()).isEqualTo("EXECUTED");
        assertThat(entry.getValue().turnIndex())
                .as("确认发生在之后的另一次 HTTP 请求里，不属于任何一轮 respond 的轮次序列 —— "
                        + "给它编一个 0 会让「这段轨迹有多长」算错")
                .isNull();
    }

    @Test
    void someoneElsesPendingIdIsNotFoundNotForbidden() {
        // 他人的 id 查不到（SQL 里带 where user_id）：返回 404 而不是 403，
        // 以免泄露「这条 id 存在但不属于你」。
        when(pendingMapper.findById(PENDING_ID, AssistantFixtures.USER)).thenReturn(null);

        assertThatThrownBy(() -> service.confirm(AssistantFixtures.USER, PENDING_ID))
                .isInstanceOf(AssistantException.class)
                .satisfies(e -> assertThat(((AssistantException) e).code())
                        .isEqualTo(AssistantException.PENDING_NOT_FOUND));
        verifyNoInteractions(todoMapper);
    }

    @Test
    void anExpiredActionIsMarkedExpiredAndRefusesToRun() {
        when(pendingMapper.findById(PENDING_ID, AssistantFixtures.USER))
                .thenReturn(pending("todo.delete", Map.of("todoId", AssistantFixtures.TODO_MINUTES), NOW.minusSeconds(1)));
        when(pendingMapper.markDecided(PENDING_ID, AssistantFixtures.USER, "EXPIRED")).thenReturn(1);

        assertThatThrownBy(() -> service.confirm(AssistantFixtures.USER, PENDING_ID))
                .isInstanceOf(AssistantException.class)
                .satisfies(e -> assertThat(((AssistantException) e).code())
                        .isEqualTo(AssistantException.PENDING_EXPIRED));
        // 过期要留下痕迹：确认时返回 410，但库里那条必须从 PENDING 迁到 EXPIRED。
        verify(pendingMapper).markDecided(PENDING_ID, AssistantFixtures.USER, "EXPIRED");
        verify(todoMapper, never()).delete(any(), any());
    }

    @Test
    void confirmingTwiceIsRefusedTheSecondTime() {
        when(pendingMapper.findById(PENDING_ID, AssistantFixtures.USER))
                .thenReturn(pending("todo.delete", Map.of("todoId", AssistantFixtures.TODO_MINUTES), NOW.plusSeconds(600)));
        when(pendingMapper.markDecided(PENDING_ID, AssistantFixtures.USER, "CONFIRMED")).thenReturn(1);
        when(todoMapper.findById(UUID.fromString(AssistantFixtures.TODO_MINUTES), AssistantFixtures.USER))
                .thenReturn(todo(AssistantFixtures.TODO_MINUTES, LocalDate.of(2026, 9, 25), null, "整理上周会议纪要"));
        when(todoMapper.delete(any(), any())).thenReturn(1);

        assertThat(service.confirm(AssistantFixtures.USER, PENDING_ID).kind())
                .isEqualTo(AssistantTurnResult.Kind.EXECUTED);

        // 第二次调用看到的状态已不是 PENDING。
        AssistantPendingActionEntity decided =
                pending("todo.delete", Map.of("todoId", AssistantFixtures.TODO_MINUTES), NOW.plusSeconds(600));
        decided.setStatus("CONFIRMED");
        when(pendingMapper.findById(PENDING_ID, AssistantFixtures.USER)).thenReturn(decided);

        assertThatThrownBy(() -> service.confirm(AssistantFixtures.USER, PENDING_ID))
                .isInstanceOf(AssistantException.class)
                .satisfies(e -> assertThat(((AssistantException) e).code())
                        .isEqualTo(AssistantException.PENDING_ALREADY_DECIDED));
        // 关键断言不是「一次都没删」，而是「总数仍然只有第一次那一次」：
        // 第一次确认本来就该删掉那条待办，若第二次被拒后又删了一次，这里才会红。
        verify(todoMapper, times(1)).delete(any(), any());
        verify(pendingMapper, times(1)).markDecided(PENDING_ID, AssistantFixtures.USER, "CONFIRMED");
    }

    /**
     * 并发双击：两次请求都读到了 PENDING，但原子抢占只让一次成功。
     * 这里手工构造「抢占影响 0 行」的情形，模拟另一个请求在两步之间抢先落库。
     */
    @Test
    void losingTheAtomicClaimOverwritesNothingAndReportsConflict() {
        when(pendingMapper.findById(PENDING_ID, AssistantFixtures.USER))
                .thenReturn(pending("todo.delete", Map.of("todoId", AssistantFixtures.TODO_MINUTES), NOW.plusSeconds(600)));
        when(pendingMapper.markDecided(PENDING_ID, AssistantFixtures.USER, "CONFIRMED")).thenReturn(0);

        assertThatThrownBy(() -> service.confirm(AssistantFixtures.USER, PENDING_ID))
                .isInstanceOf(AssistantException.class)
                .satisfies(e -> assertThat(((AssistantException) e).code())
                        .isEqualTo(AssistantException.PENDING_ALREADY_DECIDED));
        verifyNoInteractions(todoMapper);
    }

    /**
     * 目标在确认前消失：由<b>动作自己</b>发现，而不是由候选清单判失效。
     *
     * <p>改动前这里拿「本轮候选清单」当判据，返回 {@code ASSISTANT_STALE_REFERENCE}。
     * 但候选清单是「最近能看到什么」的<b>窗口</b>，不是「对象是否存在」—— 于是窗口外的合法引用
     * 被一并误杀（见 {@link #confirmationDoesNotDependOnTheCandidateWindow()}）。
     * 现在由 {@code require(userId, id)} 发现，错误码来自动作本身，对用户的指引更准。
     */
    @Test
    void aVanishedTargetIsRefusedByTheActionItselfNotByTheCandidateWindow() {
        when(pendingMapper.findById(PENDING_ID, AssistantFixtures.USER))
                .thenReturn(pending("todo.complete", Map.of("todoId", AssistantFixtures.TODO_QUOTE, "completed", true), NOW.plusSeconds(600)));
        when(pendingMapper.markDecided(PENDING_ID, AssistantFixtures.USER, "CONFIRMED")).thenReturn(1);
        // 从弹出卡片到点确认之间，这条待办被删了。
        when(todoMapper.findById(UUID.fromString(AssistantFixtures.TODO_QUOTE), AssistantFixtures.USER))
                .thenReturn(null);

        AssistantTurnResult result = service.confirm(AssistantFixtures.USER, PENDING_ID);

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.ERROR);
        assertThat(result.errorCode()).isEqualTo(ToolExecutionException.TODO_NOT_FOUND);
        verify(todoMapper, never()).setCompleted(any(), any(), anyBoolean());

        ArgumentCaptor<AssistantAuditService.Entry> entry =
                ArgumentCaptor.forClass(AssistantAuditService.Entry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().outcome()).isEqualTo("FAILED");
        assertThat(entry.getValue().errorCode()).isEqualTo(ToolExecutionException.TODO_NOT_FOUND);
    }

    /**
     * 确认<b>不看</b>候选窗口 —— 这是「先只读检索、再对检索结果下写动作」能走通的全部理由。
     *
     * <p>取值方式是这个用例的关键：目标 id 选一个**任何**候选集里都不会出现的值，
     * 同时让执行路径成功。若确认路径又去看候选窗口，这里立刻变红 ——
     * 走查里那次「卡片正常显示、一点确认就 ASSISTANT_STALE_REFERENCE」正是这个形状。
     */
    @Test
    void confirmationDoesNotDependOnTheCandidateWindow() {
        String offWindow = "abcdefab-1234-4567-89ab-abcdefabcdef";
        when(pendingMapper.findById(PENDING_ID, AssistantFixtures.USER))
                .thenReturn(pending("todo.complete", Map.of("todoId", offWindow, "completed", true), NOW.plusSeconds(600)));
        when(pendingMapper.markDecided(PENDING_ID, AssistantFixtures.USER, "CONFIRMED")).thenReturn(1);
        when(todoMapper.findById(UUID.fromString(offWindow), AssistantFixtures.USER))
                .thenReturn(todo(offWindow, LocalDate.of(2026, 9, 22), LocalTime.of(15, 0), "窗口外的待办"));
        when(todoMapper.setCompleted(any(), any(), anyBoolean())).thenReturn(1);

        AssistantTurnResult result = service.confirm(AssistantFixtures.USER, PENDING_ID);

        assertThat(result.kind()).as("窗口外的引用照样能确认").isEqualTo(AssistantTurnResult.Kind.EXECUTED);
        verify(todoMapper).setCompleted(UUID.fromString(offWindow), AssistantFixtures.USER, true);
    }

    // ---------- 取消 ----------

    @Test
    void cancellingNeverExecutesAnything() {
        when(pendingMapper.findById(PENDING_ID, AssistantFixtures.USER))
                .thenReturn(pending("todo.delete", Map.of("todoId", AssistantFixtures.TODO_MINUTES), NOW.plusSeconds(600)));
        when(pendingMapper.markDecided(PENDING_ID, AssistantFixtures.USER, "CANCELLED")).thenReturn(1);

        AssistantTurnResult result = service.cancel(AssistantFixtures.USER, PENDING_ID);

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.ANSWER);
        assertThat(result.message()).contains("已取消");
        verifyNoInteractions(todoMapper);

        ArgumentCaptor<AssistantAuditService.Entry> entry =
                ArgumentCaptor.forClass(AssistantAuditService.Entry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().outcome()).isEqualTo("CANCELLED");
    }

    @Test
    void cancellingAnAlreadyCancelledActionIsIdempotent() {
        AssistantPendingActionEntity cancelled =
                pending("todo.delete", Map.of("todoId", AssistantFixtures.TODO_MINUTES), NOW.plusSeconds(600));
        cancelled.setStatus("CANCELLED");
        when(pendingMapper.findById(PENDING_ID, AssistantFixtures.USER)).thenReturn(cancelled);

        AssistantTurnResult result = service.cancel(AssistantFixtures.USER, PENDING_ID);

        assertThat(result.kind()).isEqualTo(AssistantTurnResult.Kind.ANSWER);
        assertThat(result.message()).contains("已经取消过");
        // 没有状态迁移就没有新审计行 —— 重复点击不该把审计流水灌满。
        verify(audit, never()).record(any());
    }

    @Test
    void cancellingACancelledActionAfterConfirmationIsRefused() {
        AssistantPendingActionEntity confirmed =
                pending("todo.delete", Map.of("todoId", AssistantFixtures.TODO_MINUTES), NOW.plusSeconds(600));
        confirmed.setStatus("CONFIRMED");
        when(pendingMapper.findById(PENDING_ID, AssistantFixtures.USER)).thenReturn(confirmed);

        assertThatThrownBy(() -> service.cancel(AssistantFixtures.USER, PENDING_ID))
                .isInstanceOf(AssistantException.class)
                .satisfies(e -> assertThat(((AssistantException) e).code())
                        .isEqualTo(AssistantException.PENDING_ALREADY_DECIDED));
    }

    @Test
    void cancellingAnExpiredActionReportsExpiry() {
        when(pendingMapper.findById(PENDING_ID, AssistantFixtures.USER))
                .thenReturn(pending("todo.delete", Map.of("todoId", AssistantFixtures.TODO_MINUTES), NOW.minusSeconds(1)));
        when(pendingMapper.markDecided(PENDING_ID, AssistantFixtures.USER, "EXPIRED")).thenReturn(1);

        assertThatThrownBy(() -> service.cancel(AssistantFixtures.USER, PENDING_ID))
                .isInstanceOf(AssistantException.class)
                .satisfies(e -> assertThat(((AssistantException) e).code())
                        .isEqualTo(AssistantException.PENDING_EXPIRED));
        verify(pendingMapper).markDecided(PENDING_ID, AssistantFixtures.USER, "EXPIRED");
    }

    // ---------- 落库与摘要 ----------

    @Test
    void creatingAPendingActionStoresTheRawArgumentsAndADatedSummary() {
        when(pendingMapper.insert(any())).thenReturn(1);
        ToolDefinition definition = registry.find("todo.update").orElseThrow();

        AssistantTurnResult.Proposal proposal = service.create(AssistantFixtures.USER, AssistantFixtures.CONVERSATION,
                definition, Map.of("todoId", AssistantFixtures.TODO_QUOTE, "date", "2026-09-23"),
                AssistantFixtures.context());

        assertThat(proposal.summary()).contains("和张总确认报价").contains("2026-09-23");

        ArgumentCaptor<AssistantPendingActionEntity> entity =
                ArgumentCaptor.forClass(AssistantPendingActionEntity.class);
        verify(pendingMapper).insert(entity.capture());
        assertThat(entity.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(entity.getValue().getArgumentsJson())
                .as("参数原样保存：确认时要拿这一份重新校验")
                .contains(AssistantFixtures.TODO_QUOTE).contains("2026-09-23");
        assertThat(entity.getValue().getChangesJson())
                .as("「给用户看的那一面」也要落库：用户按下确认时同意的是他当时看到的那份文字，"
                        + "事后要能复原 —— 包括卡片有没有告诉他「改前」是什么")
                .contains("\"when\"").contains("2026-09-22 15:00");
        assertThat(proposal.changes()).extracting(AssistantTurnResult.Proposal.Change::field)
                .containsExactly("when");
        assertThat(entity.getValue().getExpiresAt())
                .isEqualTo(NOW.plusSeconds(CONFIG.pendingTtlSeconds()));
    }

    @Test
    void everySummaryCarriesTheTitleAndTheDate() {
        AssistantContext context = AssistantFixtures.context();

        assertThat(service.summarise(AssistantFixtures.USER, "todo.create",
                Map.of("title", "和张总确认报价", "date", "2026-09-22", "time", "15:00"), context))
                .contains("和张总确认报价").contains("2026-09-22 15:00");

        assertThat(service.summarise(AssistantFixtures.USER, "todo.complete",
                Map.of("todoId", AssistantFixtures.TODO_QUOTE, "completed", true), context))
                .contains("标记完成").contains("和张总确认报价").contains("2026-09-22 15:00");

        assertThat(service.summarise(AssistantFixtures.USER, "todo.complete",
                Map.of("todoId", AssistantFixtures.TODO_QUOTE, "completed", false), context))
                .contains("标回未完成");

        assertThat(service.summarise(AssistantFixtures.USER, "todo.delete",
                Map.of("todoId", AssistantFixtures.TODO_MINUTES), context))
                .contains("删除待办").contains("整理上周会议纪要").contains("2026-09-25");

        assertThat(service.summarise(AssistantFixtures.USER, "todo.update",
                Map.of("todoId", AssistantFixtures.TODO_QUOTE, "title", "和张总确认最终报价"), context))
                .contains("和张总确认报价").contains("和张总确认最终报价");
    }

    // ---------- 卡片的「变更前后」 ----------

    @Test
    void aCardShowsWhatWillChangeAndWhatItWasBefore() {
        AssistantPendingActionService.Card card = service.card(AssistantFixtures.USER, "todo.update",
                Map.of("todoId", AssistantFixtures.TODO_QUOTE, "title", "和张总确认最终报价"),
                AssistantFixtures.context());

        assertThat(card.summary()).contains("和张总确认报价").contains("和张总确认最终报价");
        assertThat(card.changes()).extracting(AssistantTurnResult.Proposal.Change::field)
                .containsExactly("title");
        AssistantTurnResult.Proposal.Change change = card.changes().get(0);
        assertThat(change.label()).isEqualTo("内容");
        assertThat(change.before())
                .as("「改前」取自候选清单 —— 那是服务端自己看到的当前值，不是模型转述的")
                .isEqualTo("和张总确认报价");
        assertThat(change.after()).isEqualTo("和张总确认最终报价");
    }

    @Test
    void dateAndTimeBecomeASingleWhenRow() {
        // 日期与时间对用户是同一件事（「什么时候」）。拆成两条会让卡片出现
        // 「日期：2026-09-22 → 空」这种读不懂的行 —— 而卡片读不懂就等于没有复核。
        AssistantPendingActionService.Card card = service.card(AssistantFixtures.USER, "todo.update",
                Map.of("todoId", AssistantFixtures.TODO_QUOTE, "date", "2026-09-24"),
                AssistantFixtures.context());

        assertThat(card.changes()).extracting(AssistantTurnResult.Proposal.Change::field)
                .containsExactly("when");
        assertThat(card.changes().get(0).before()).isEqualTo("2026-09-22 15:00");
        assertThat(card.changes().get(0).after()).isEqualTo("2026-09-24");
    }

    @Test
    void aChangeNeverInventsABeforeItCannotVerify() {
        // 备注不在候选清单里，「改前」无从得知。这里必须是 null（前端渲染成「当前未知」），
        // 而不是拿候选里别的字段来充数：编出来的「改前」会被用户当成事实去核对，比不显示更糟。
        AssistantPendingActionService.Card card = service.card(AssistantFixtures.USER, "todo.update",
                Map.of("todoId", AssistantFixtures.TODO_QUOTE, "note", "已确认报价"),
                AssistantFixtures.context());

        assertThat(card.changes()).hasSize(1);
        assertThat(card.changes().get(0).label()).isEqualTo("备注");
        assertThat(card.changes().get(0).before()).isNull();
        assertThat(card.changes().get(0).after()).isEqualTo("已确认报价");
    }

    @Test
    void aChangeForAnUnresolvableTargetDoesNotInventABeforeEither() {
        // 目标不在候选里（摘要那边已经退化成显示 id 了）。此时连名称都不可信，
        // 更不能编一个「改前」出来 —— 那会让用户以为模型看的和他是同一条待办。
        AssistantPendingActionService.Card card = service.card(AssistantFixtures.USER, "todo.update",
                Map.of("todoId", "99999999-9999-4999-8999-999999999999",
                        "title", "和张总确认最终报价"),
                AssistantFixtures.context());

        assertThat(card.changes()).hasSize(1);
        assertThat(card.changes().get(0).before()).isNull();
    }

    @Test
    void aPinCardCarriesThePinnedStateTheServerActuallySaw() {
        AssistantPendingActionService.Card pin = service.card(AssistantFixtures.USER, "conversation.pin",
                Map.of("conversationRef", AssistantFixtures.CONVERSATION_SEA, "pinned", true),
                AssistantFixtures.context());
        assertThat(pin.changes()).hasSize(1);
        assertThat(pin.changes().get(0).before()).as("候选里这条本来未置顶").isEqualTo("未置顶");
        assertThat(pin.changes().get(0).after()).isEqualTo("已置顶");

        // 反方向：候选里已置顶的那条，取消置顶时「改前」应当是「已置顶」。
        // 只测一个方向的话，把 before 写死成「未置顶」也能全绿。
        AssistantPendingActionService.Card unpin = service.card(AssistantFixtures.USER, "conversation.pin",
                Map.of("conversationRef", AssistantFixtures.CONVERSATION_ZHOU, "pinned", false),
                AssistantFixtures.context());
        assertThat(unpin.changes().get(0).before()).isEqualTo("已置顶");
        assertThat(unpin.changes().get(0).after()).isEqualTo("未置顶");
    }

    @Test
    void aProfileCardShowsTheDisplayNameTheServerWillOverwrite() {
        // 事故复盘（2026-09-23）：模型把「改备注」错当成 contact.update_profile，覆盖了渠道同步来的昵称，
        // 而旧卡片的「改前」是「当前未知」—— 用户点确认时看不到会被覆盖的值。
        // 「改前」现在从库里取真值（与 todo.update 的候选值同一等级：服务端自己看到的当前值）。
        ContactEntity zhou = new ContactEntity();
        zhou.setDisplayName("calm1026");
        zhou.setRoleTitle("采购经理");
        when(contacts.findByIdAndOwner(AssistantFixtures.CONTACT_ZHOU, AssistantFixtures.USER))
                .thenReturn(Optional.of(zhou));

        AssistantPendingActionService.Card card = service.card(AssistantFixtures.USER,
                "contact.update_profile",
                Map.of("contactRef", AssistantFixtures.CONTACT_ZHOU_REF,
                        "displayName", "张百凡", "roleTitle", ""),
                AssistantFixtures.context());

        assertThat(card.changes()).extracting(AssistantTurnResult.Proposal.Change::field)
                .containsExactly("displayName", "roleTitle");
        assertThat(card.changes().get(0).before()).as("昵称是被覆盖的那个值，必须看得见")
                .isEqualTo("calm1026");
        assertThat(card.changes().get(0).after()).isEqualTo("张百凡");
        assertThat(card.changes().get(1).before()).isEqualTo("采购经理");
        assertThat(card.changes().get(1).after()).isEqualTo("（清除）");
    }

    @Test
    void aProfileCardLeavesTheBeforeUnknownWhenTheContactIsNotYoursOrIsMissing() {
        // 库里查不到（不是你名下 / 已删除 / ref 坏了）→ 「改前」留 null。
        // 绝不拿候选里的名字充数：候选的 name 在显示名为空时会退回备注，那是错的值。
        AssistantPendingActionService.Card card = service.card(AssistantFixtures.USER,
                "contact.update_profile",
                Map.of("contactRef", AssistantFixtures.CONTACT_ZHOU_REF, "displayName", "张百凡"),
                AssistantFixtures.context());

        assertThat(card.changes()).hasSize(1);
        assertThat(card.changes().get(0).before()).isNull();
        verify(contacts).findByIdAndOwner(AssistantFixtures.CONTACT_ZHOU, AssistantFixtures.USER);
    }

    @Test
    void actionsWithoutABeforeCarryNoChangeRows() {
        // 新建与删除没有「改前」可言：硬造一条 before=null 的行只会给卡片添一行噪声。
        assertThat(service.card(AssistantFixtures.USER, "todo.create",
                Map.of("title", "新待办", "date", "2026-09-23"), AssistantFixtures.context()).changes())
                .isEmpty();
        assertThat(service.card(AssistantFixtures.USER, "todo.delete",
                Map.of("todoId", AssistantFixtures.TODO_QUOTE), AssistantFixtures.context()).changes())
                .isEmpty();
    }

    @Test
    void theCardAcceptsLongFormTextNowThatTheColumnIsText() {
        // 列已升为 text（V90）—— 发邮件这类动作的正文天然是长文本，
        // 而「卡片显示不下正文」的后果是用户在看不见内容的情况下点确认。
        // 这里用一个 1200 字的正文，断言它没有被截到 500。
        String body = "正".repeat(1200);
        AssistantPendingActionService.Card card = service.card(AssistantFixtures.USER, "todo.create",
                Map.of("title", body, "date", "2026-09-23"), AssistantFixtures.context());

        assertThat(card.summary().length()).isGreaterThan(500);
    }

    @Test
    void aSummaryIsStillBoundedNowThatTheColumnIsText() {
        // 上限没有消失，只是换了理由：它防的不再是「列装不下」，而是
        // 「模型往参数里塞一段无界文本，我们把它原样搬进 UI」（兜底分支会序列化整个参数对象）。
        String hugeTitle = "标".repeat(5000);
        String summary = service.summarise(AssistantFixtures.USER, "todo.create", Map.of("title", hugeTitle, "date", "2026-09-22"),
                AssistantFixtures.context());

        assertThat(summary.length()).isLessThanOrEqualTo(4000);
    }

    // ---------- 对外发送的卡片（用户复核的是「全文」而不是「某个字段」） ----------

    @Test
    void anOutboundCardShowsTheActualAddressAndTheWholeBody() {
        String body = "王工你好，\n" + "正".repeat(300) + "\n价格按上次谈的走。";
        AssistantPendingActionService withAddress = serviceWithRecipient(
                new OutboundMessageService.Recipient(UUID.randomUUID(), "zhou@example.com", "email"));

        AssistantPendingActionService.Card card = withAddress.card(AssistantFixtures.USER,
                "message.send_email",
                Map.of("contactRef", AssistantFixtures.CONTACT_ZHOU_REF, "subject", "报价确认", "body", body),
                AssistantFixtures.context());

        assertThat(card.summary()).contains("周明").contains("报价确认");
        assertThat(card.summary())
                .as("只写「发给老王」不是一条可核对的信息：老王可能有多个邮箱，"
                        + "用户点头的其实是他没看见的那个地址")
                .contains("zhou@example.com");
        assertThat(card.summary())
                .as("正文必须一字不少 —— 用户点确认时同意的那份文字就是它")
                .contains(body);
        assertThat(card.changes())
                .as("发送没有「改前」；硬造一条 before=null 的行会被渲染成「当前未知 → 地址」")
                .isEmpty();
    }

    @Test
    void anOutboundCardSaysSoWhenThereIsNoAddressInsteadOfLookingNormal() {
        // 主 service 里的解析器是 mock（返回 null），正对应「这个联系人档案里没有可用地址」。
        AssistantPendingActionService.Card card = service.card(AssistantFixtures.USER, "message.send_email",
                Map.of("contactRef", AssistantFixtures.CONTACT_ZHOU_REF, "subject", "报价确认", "body", "正文"),
                AssistantFixtures.context());

        assertThat(card.summary())
                .as("装作正常会让用户对着这张卡片点确认，然后拿到一个失败")
                .contains("找不到")
                .contains("发不出去");
    }

    @Test
    void anOutboundCardAlwaysFitsInTheSummaryBudgetSoTheBodyIsNeverSilentlyCut() {
        // 最坏情况：姓名 100（contacts.display_name varchar(100)）、
        // 地址 255（contact_identities.identity_value varchar(255)，见 V1）、
        // 主题 300 与正文 3000（MessageSendAssistantTools 的声明上限）。
        // 这四个数任一被调大这条用例就会红 —— 而后果是卡片悄悄少一段正文，
        // 用户在没看全的情况下点了「确认发送」（卡片正文是裸截断，不带标记）。
        String name = "名".repeat(AssistantPendingActionService.CARD_NAME_MAX);
        String address = "a".repeat(243) + "@example.com";     // 恰好 255
        String subject = "主".repeat(MessageSendAssistantTools.SUBJECT_MAX_CHARS);
        String body = "正".repeat(MessageSendAssistantTools.EMAIL_BODY_MAX_CHARS);

        AssistantPendingActionService withAddress = serviceWithRecipient(
                new OutboundMessageService.Recipient(UUID.randomUUID(), address, "email"));
        AssistantPendingActionService.Card email = withAddress.card(AssistantFixtures.USER,
                "message.send_email",
                Map.of("contactRef", AssistantFixtures.CONTACT_ZHOU_REF, "subject", subject, "body", body),
                longNameContext(name));

        assertThat(email.summary().length()).isLessThanOrEqualTo(4000);
        assertThat(email.summary()).contains(body).contains(address);

        // chatapp 那条同理：手机号最多 255（同一列），正文上限 2000。
        String phone = "8".repeat(255);
        String text = "文".repeat(MessageSendAssistantTools.CHATAPP_TEXT_MAX_CHARS);
        AssistantPendingActionService withPhone = serviceWithRecipient(
                new OutboundMessageService.Recipient(UUID.randomUUID(), phone, "chatapp"));
        AssistantPendingActionService.Card chatApp = withPhone.card(AssistantFixtures.USER,
                "message.send_chatapp",
                Map.of("contactRef", AssistantFixtures.CONTACT_ZHOU_REF, "text", text),
                longNameContext(name));

        assertThat(chatApp.summary().length()).isLessThanOrEqualTo(4000);
        assertThat(chatApp.summary()).contains(text).contains(phone);
    }

    @Test
    void aChatAppCardNamesThePhoneItWillActuallySendTo() {
        AssistantPendingActionService withPhone = serviceWithRecipient(
                new OutboundMessageService.Recipient(UUID.randomUUID(), "8613800000000", "chatapp"));

        AssistantPendingActionService.Card card = withPhone.card(AssistantFixtures.USER,
                "message.send_chatapp",
                Map.of("contactRef", AssistantFixtures.CONTACT_ZHOU_REF, "text", "货已发出"),
                AssistantFixtures.context());

        assertThat(card.summary()).contains("周明").contains("8613800000000").contains("货已发出");
    }

    /**
     * 一个把解析结果固定住的 service。
     *
     * <p>为什么不能直接用主 {@code service}：那里注入的解析器是 mock，{@code resolve} 恒为
     * {@code null} —— 那正好覆盖「没有地址」那一支，却覆盖不到地址显示。
     */
    private AssistantPendingActionService serviceWithRecipient(OutboundMessageService.Recipient recipient) {
        OutboundMessageService outbound = mock(OutboundMessageService.class);
        when(outbound.resolve(any(), any(), any())).thenReturn(recipient);
        return new AssistantPendingActionService(pendingMapper, parser, registry, audit, CONFIG,
                AssistantFixtures.objectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), outbound, contacts);
    }

    /** 一个只有联系人候选、且名字超长的上下文（用于算卡片的最坏长度）。 */
    private static AssistantContext longNameContext(String name) {
        return new AssistantContext("Asia/Shanghai", AssistantFixtures.TODAY, "星期一", List.of(
                new TodoCandidates(70, List.of()),
                new ContactCandidates(ContactCandidateProvider.LIMIT,
                        List.of(new ContactCandidates.Item(AssistantFixtures.CONTACT_ZHOU_REF, name, null)))));
    }

    // ---------- 夹具 ----------

    private static AssistantPendingActionEntity pending(String tool, Map<String, Object> arguments, Instant expiresAt) {
        AssistantPendingActionEntity entity = new AssistantPendingActionEntity();
        entity.setId(PENDING_ID);
        entity.setUserId(AssistantFixtures.USER);
        entity.setConversationId(AssistantFixtures.CONVERSATION);
        entity.setToolName(tool);
        try {
            entity.setArgumentsJson(AssistantFixtures.objectMapper().writeValueAsString(arguments));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        entity.setSummary("摘要");
        entity.setStatus("PENDING");
        entity.setExpiresAt(expiresAt);
        return entity;
    }

    private static TodoItemEntity todo() {
        return todo(AssistantFixtures.TODO_QUOTE, LocalDate.of(2026, 9, 22), LocalTime.of(15, 0), "和张总确认报价");
    }

    private static TodoItemEntity todo(String id, LocalDate date, LocalTime time, String title) {
        TodoItemEntity item = new TodoItemEntity();
        item.setId(UUID.fromString(id));
        item.setUserId(AssistantFixtures.USER);
        item.setDueDate(date);
        item.setDueTime(time);
        item.setTitle(title);
        return item;
    }
}
