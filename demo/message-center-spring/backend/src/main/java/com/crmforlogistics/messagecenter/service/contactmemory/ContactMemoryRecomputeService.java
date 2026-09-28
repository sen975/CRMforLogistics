package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.entity.ContactMemoryStateEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * 「帮我重算他的 AI 画像 / AI 标签」—— 这场戏里唯一的手动触发入口。
 *
 * <h2>它做的事只有一件：把这位联系人放回待处理队列</h2>
 * 记忆流水线是<b>增量</b>的（{@code ContactMemoryWorker} 只取游标之后的入站消息，
 * 没有新消息时<b>直接跳过、不调 LLM</b>）。所以「重算」的诚实语义是<b>催一下</b>：
 * 把还没进画像的往来内容尽快处理掉。它<b>不是</b>「把历史重新算一遍」——
 * 那会把同一条消息当成新证据重复累积（置信度虚高、画像被反复改写），
 * 属于另一件需要单独拍板的事，不是这里顺手能给的。
 *
 * <h2>没有新内容时什么都不做，也什么都不写</h2>
 * 「有没有新内容」这条判据不在这里，而在 {@code ContactMemoryStateMapper.markDirtyForRecompute}
 * 的 SQL 里 —— 与自动路径 {@code markStaleDirty} 同一条，只写一份。
 * 结果是 {@link Outcome#NOTHING_NEW} 时，库里一个字节都没动：用户得到的回答是
 * 「没有新的往来内容，这次重算不会产生变化」，而这正是真话。
 *
 * <h2>为什么不在这里把 worker 直接跑一遍</h2>
 * 跑一轮会调外部模型、耗时以十秒计，而这条链路是「提交后台任务」的语义
 * （与 {@code AiTopicService.retryGeneration} 一致：入队 + 明确回「已提交」）。
 * 更要紧的是：处理窗口由 {@code ContactMemoryScheduler} 决定（默认每天一个很窄的窗口），
 * 绕过它就需要把「手动」与「定时」两条调用路径都塞进 worker —— 那是改运行面，不是加工具。
 *
 * <h2>归属判据与读路径完全一致</h2>
 * 记忆各表以 {@code contacts.created_by} 为归属人，因此这里用的是
 * {@code ContactMapper.findByIdAndOwner} —— 与 {@code ContactMemoryQueryService.findForOwner}
 * 同一个方法。可见性更宽的那条（管理员 / 团队分配，{@code findAccessibleById}）在这里
 * <b>刻意不用</b>：能看见一个人，与能替他重算画像，是两件事。归属对不上时返回
 * {@link Optional#empty()}，由调用方如实转述。
 */
@Service
public class ContactMemoryRecomputeService {

    /** 手动重算的结果。四档对应四种对用户完全不同的话，所以不合并成 boolean。 */
    public enum Outcome {
        /** 已标脏，等待下一次处理窗口。 */
        SUBMITTED,
        /** 已经在队列里（DIRTY / RETRY_WAIT），不必重复提交。 */
        ALREADY_PENDING,
        /** 正在处理中。 */
        IN_PROGRESS,
        /** 没有比已处理游标更新的入站消息，重算不会有任何变化（且没有写库）。 */
        NOTHING_NEW
    }

    // 只列会被这里判到的三种状态；CLEAN / FAILED 落在「可以尝试标脏」那一侧，
    // 由 SQL 的 where 决定它们最终会怎样（这也是「有没有新内容」判据只有一份的原因）。
    private static final String STATUS_DIRTY = "DIRTY";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_RETRY_WAIT = "RETRY_WAIT";

    private final ContactMapper contacts;
    private final ContactMemoryStateMapper states;
    private final Clock clock;

    public ContactMemoryRecomputeService(ContactMapper contacts,
                                         ContactMemoryStateMapper states,
                                         Clock clock) {
        this.contacts = contacts;
        this.states = states;
        this.clock = clock;
    }

    /**
     * 请求一次重算。
     *
     * @return 空表示「这位联系人的记忆不在你名下」（或联系人已不可用）。
     *         两类原因刻意不区分：措辞由工具层统一收敛，避免把「这条 id 是存在的」漏给另一个账号。
     */
    @Transactional
    public Optional<Outcome> requestRecompute(UUID userId, UUID contactId) {
        if (userId == null || contactId == null) {
            return Optional.empty();
        }
        if (contacts.findByIdAndOwner(contactId, userId).isEmpty()) {
            return Optional.empty();
        }

        ContactMemoryStateEntity state = states.findByOwnerAndContact(userId, contactId).orElse(null);
        String status = state == null ? null : state.getStatus();
        if (STATUS_PROCESSING.equals(status)) {
            return Optional.of(Outcome.IN_PROGRESS);
        }
        if (STATUS_DIRTY.equals(status) || STATUS_RETRY_WAIT.equals(status)) {
            // 已经排着队了。这里再标一次脏不会更快，只会让用户以为「刚才那次没提交成功」。
            return Optional.of(Outcome.ALREADY_PENDING);
        }

        // 只剩 CLEAN / FAILED / 根本没有状态行。「有没有新内容」交给 SQL 判 ——
        // 影响 0 行就是没有，此时状态<b>保持 CLEAN 不动</b>：改成 DIRTY 会让 worker 每天空跑一轮。
        int marked = states.markDirtyForRecompute(userId, contactId, clock.instant());
        return Optional.of(marked == 1 ? Outcome.SUBMITTED : Outcome.NOTHING_NEW);
    }
}
