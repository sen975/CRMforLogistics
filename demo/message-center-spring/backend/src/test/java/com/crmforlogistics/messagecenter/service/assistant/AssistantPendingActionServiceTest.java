package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.AssistantConfig;
import com.crmforlogistics.messagecenter.entity.AssistantPendingActionEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.TodoItemEntity;
import com.crmforlogistics.messagecenter.mapper.AssistantPendingActionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.TodoItemMapper;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ChatAppTemplateAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.MessageSendAssistantTools;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolDefinition;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolExecutionException;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolInputValidator;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolRegistry;
import com.crmforlogistics.messagecenter.service.channel.OutboundMessageService;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import com.crmforlogistics.messagecenter.service.whatsapp.template.WhatsAppTemplateValidator;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
        assertThat(result.errorCode()).isEqualTo(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND);
        verify(todoMapper, never()).setCompleted(any(), any(), anyBoolean());

        ArgumentCaptor<AssistantAuditService.Entry> entry =
                ArgumentCaptor.forClass(AssistantAuditService.Entry.class);
        verify(audit).record(entry.capture());
        assertThat(entry.getValue().outcome()).isEqualTo("FAILED");
        assertThat(entry.getValue().errorCode()).isEqualTo(ToolExecutionException.FORBIDDEN_OR_NOT_FOUND);
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

    // ---------- 模板申请的卡片（提交给外部平台，这边改不掉也撤不回） ----------

    /**
     * 模板卡片与发送卡片同规格：用户复核的是<b>全文</b>，不是「改哪个字段」。
     *
     * <p>理由与发送那边不同：那两张卡片拦的是「发错人/发错话」，这张拦的是「提交错了」——
     * 内容一进 Meta 的审核队列，这边既改不掉也撤不回；「类别选错」还是最常见的拒因之一。
     * 所以标题/正文/页脚/按钮一个都不能少，而且顺序必须与平台上的顺序一致，
     * 用户才拿得到一张能和后台对着看的卡片。
     *
     * <p>页脚与按钮还各带一个 ［页脚］/［按钮］ 标记：它们在客户手机上本来就不是正文的一部分，
     * 卡片上不加标记就会和正文连成一句，被读成「内容里怎么多写了这个」。
     */
    @Test
    void aTemplateCardShowsEveryPartThatWillBeSubmitted() {
        AssistantPendingActionService.Card card = service.card(AssistantFixtures.USER,
                ChatAppTemplateAssistantTools.TOOL_TEMPLATE_APPLY, templateArguments(),
                AssistantFixtures.context());

        assertThat(card.summary())
                .contains("quote_follow_up").contains("MARKETING").contains("pt_BR")
                .contains("报价跟进").contains("$(customer_name)，您好").contains("回复 TD 退订")
                .contains("查看报价").contains("https://example.com/quote/123")
                .contains("查看舱位").contains("https://example.com/schedule/456");
        assertThat(card.summary())
                .as("顺序即平台上的顺序：标题 → 正文 → 页脚 → 按钮 1 → 按钮 2")
                .contains("报价跟进\n$(customer_name)，您好")
                .contains("已备好\n［页脚］回复 TD 退订")
                .contains("［页脚］回复 TD 退订\n［按钮］查看报价 → https://example.com/quote/123"
                        + "\n［按钮］查看舱位 → https://example.com/schedule/456");
        assertThat(card.summary().split("［按钮］", -1))
                .as("两个按钮一个都不能少")
                .hasSize(ChatAppTemplateAssistantTools.MAX_URL_BUTTONS + 1);
        assertThat(card.changes())
                .as("提交没有「改前」；硬造一条 before=null 的行会被渲染成一次修改")
                .isEmpty();
    }

    @Test
    void aTemplateCardLeavesTheLanguageOffWhenTheModelDidNotPickOne() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("name", "order_update");
        arguments.put("category", "UTILITY");
        arguments.put("body", "您的订单已发出");

        AssistantPendingActionService.Card card = service.card(AssistantFixtures.USER,
                ChatAppTemplateAssistantTools.TOOL_TEMPLATE_APPLY, arguments, AssistantFixtures.context());

        assertThat(card.summary())
                .as("模型没传语言时它会落在一个服务端默认值上（zh_CN）；写上去会让用户以为那是自己选的")
                .isEqualTo("申请 WhatsApp 模板「order_update」（UTILITY），提交给平台的内容：\n您的订单已发出");
    }

    /**
     * 最坏情况下的长度：把声明里的每个 {@code maxLength} 都用满。
     *
     * <p>这个用例是**为将来改上限的人写的**：卡片正文是裸截断（不带标记），
     * 一旦超预算，用户会对着一个只显示了一部分的模板按下确认，而这次提交撤不回。
     * 所以「卡片一定显示得全」这条保证必须有人守着 —— 任一上限被调大，这里就会红。
     * （同 {@code anOutboundCardAlwaysFitsInTheSummaryBudgetSoTheBodyIsNeverSilentlyCut}。）
     */
    @Test
    void aTemplateCardAlwaysFitsInTheSummaryBudgetSoNoLineIsSilentlyCut() {
        String name = "n".repeat(ChatAppTemplateAssistantTools.NAME_MAX_CHARS);
        String language = "l".repeat(ChatAppTemplateAssistantTools.LANGUAGE_MAX_CHARS);
        String header = "头".repeat(WhatsAppTemplateValidator.MAX_HEADER_OR_FOOTER_LENGTH);
        String body = "正".repeat(WhatsAppTemplateValidator.MAX_BODY_LENGTH);
        String footer = "尾".repeat(WhatsAppTemplateValidator.MAX_HEADER_OR_FOOTER_LENGTH);
        String buttonText = "按".repeat(WhatsAppTemplateValidator.MAX_HEADER_OR_FOOTER_LENGTH);

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("name", name);
        arguments.put("category", "MARKETING");
        arguments.put("language", language);
        arguments.put("headerText", header);
        arguments.put("body", body);
        arguments.put("footerText", footer);
        // 按钮槽位全部用满，且每个 URL 的收尾各不相同 —— 探针必须能分辨出是哪一个被截了。
        // （末尾那几个字符就是探针：卡片是裸截断，不留标记，超预算时最先消失。）
        for (int index = 1; index <= ChatAppTemplateAssistantTools.MAX_URL_BUTTONS; index++) {
            String probe = "END" + index + "!";
            String url = "https://example.com/"
                    + "u".repeat(ChatAppTemplateAssistantTools.BUTTON_URL_MAX_CHARS - 20 - probe.length())
                    + probe;
            assertThat(url).as("第 %d 个链接要用满上限", index)
                    .hasSize(ChatAppTemplateAssistantTools.BUTTON_URL_MAX_CHARS);
            arguments.put(ChatAppTemplateAssistantTools.buttonTextKey(index), buttonText);
            arguments.put(ChatAppTemplateAssistantTools.buttonUrlKey(index), url);
        }

        AssistantPendingActionService.Card card = service.card(AssistantFixtures.USER,
                ChatAppTemplateAssistantTools.TOOL_TEMPLATE_APPLY, arguments, AssistantFixtures.context());

        // 4000 是 AssistantPendingActionService.SUMMARY_MAX（私有，故此处写死；同上面那条发送用例）。
        assertThat(card.summary().length()).isLessThanOrEqualTo(4000);
        assertThat(card.summary())
                .as("卡片是裸截断：这里少一个字，用户就少看一个字")
                .contains(header).contains(body).contains(footer).contains(buttonText);
        for (int index = 1; index <= ChatAppTemplateAssistantTools.MAX_URL_BUTTONS; index++) {
            assertThat(card.summary())
                    .as("第 %d 个链接被截断了（它的探针不见了）—— 按钮槽位填满时"
                            + "这个预算就不再宽松，要么调小上限，要么让卡片多显示一点", index)
                    .contains("END" + index + "!");
        }
        assertThat(card.summary().split("［按钮］", -1))
                .hasSize(ChatAppTemplateAssistantTools.MAX_URL_BUTTONS + 1);
    }

    /** 一份「每个可选项都给了」的模板参数 —— 工具侧会把它组装成 HEADER/BODY/FOOTER/BUTTONS。 */
    private static Map<String, Object> templateArguments() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("accountRef", "CHATAPP_ACCOUNT:11111111-1111-4111-8111-111111111111");
        arguments.put("name", "quote_follow_up");
        arguments.put("category", "MARKETING");
        arguments.put("language", "pt_BR");
        arguments.put("headerText", "报价跟进");
        arguments.put("body", "$(customer_name)，您好，关于贵司的报价我们已备好");
        arguments.put("footerText", "回复 TD 退订");
        arguments.put("buttonText", "查看报价");
        arguments.put("buttonUrl", "https://example.com/quote/123");
        arguments.put("button2Text", "查看舱位");
        arguments.put("button2Url", "https://example.com/schedule/456");
        return arguments;
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

    // ---------- 素材入库卡片 ----------

    /**
     * 素材入库卡片：地址必须完整显示，账号名要说出来。
     *
     * <p>这张卡片回答的是「从哪儿抓这张图」。用户这一条消息里可能有几个链接，
     * 模型挑错了只有在这里才看得出来 —— 所以地址不摘要、不省略。
     * 断言里排除 {@code TEMPLATE_MEDIA_LINK:} 这个前缀：它对用户是噪声，
     * 他要核对的是那张图，不是候选 id 的形状。
     */
    @Test
    void aMediaUploadCardShowsTheAddressThatWillBeFetched() {
        UUID account = UUID.randomUUID();
        String url = "https://cdn.example.com/quote.png";
        AssistantContext context = AssistantFixtures.context().withCandidateSet(
                new ChatAppAccountCandidates(ChatAppAccountCandidates.LIMIT,
                        List.of(new ChatAppAccountCandidates.Item(
                                ChatAppAccountCandidates.idOf(account), "悦为", true))));

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("accountRef", ChatAppAccountCandidates.idOf(account));
        arguments.put(ChatAppTemplateAssistantTools.LINK_REF, TemplateMediaLinkCandidates.idOf(url));

        AssistantPendingActionService.Card card = service.card(AssistantFixtures.USER,
                ChatAppTemplateAssistantTools.TOOL_TEMPLATE_MEDIA_UPLOAD, arguments, context);

        assertThat(card.summary())
                .contains("素材库")
                .contains(url)
                .doesNotContain(TemplateMediaLinkCandidates.TYPE);
        assertThat(card.changes())
                .as("存素材没有「改前」；硬造一条 before=null 会被渲染成一次修改")
                .isEmpty();
    }

    /**
     * 拿不到账号名时退回 {@code accountRef}，<b>不编一个名字</b>。
     *
     * <p>编出来的名字会让用户在一张给他核对用的卡片上核对一个不存在的账号
     * （同工具层 {@code accountTarget} 的口径）。账号候选缺席是真实情形：
     * 那一组候选只在调过 {@code chatapp.template_list} 的轮次里才有。
     */
    @Test
    void aMediaUploadCardFallsBackToTheReferenceWhenTheCandidateIsGone() {
        UUID account = UUID.randomUUID();
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("accountRef", ChatAppAccountCandidates.idOf(account));
        arguments.put(ChatAppTemplateAssistantTools.LINK_REF,
                TemplateMediaLinkCandidates.idOf("https://cdn.example.com/quote.png"));

        AssistantPendingActionService.Card card = service.card(AssistantFixtures.USER,
                ChatAppTemplateAssistantTools.TOOL_TEMPLATE_MEDIA_UPLOAD, arguments,
                AssistantFixtures.context());

        assertThat(card.summary()).contains(ChatAppAccountCandidates.idOf(account));
    }
}
