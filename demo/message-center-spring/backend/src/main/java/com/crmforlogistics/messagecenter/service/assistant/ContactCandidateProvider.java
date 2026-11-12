package com.crmforlogistics.messagecenter.service.assistant;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 联系人候选的来源。检索与跨请求恢复用<b>同一套</b>投影与上限：
 *
 * <ul>
 *   <li>{@link #recent(UUID)} —— 提问前注入的候选窗口（「最近在跟谁往来」）；</li>
 *   <li>{@link #search(UUID, String)} —— {@code contact.search} 只读工具的检索，
 *       关键词可以命中窗口<b>之外</b>的联系人，结果替换掉候选窗口，供下一轮 {@code contact.brief} 引用。</li>
 *   <li>{@link #refresh(UUID, List)} —— 跨请求恢复已发现的引用；逐条重新授权并重新投影渠道。</li>
 * </ul>
 *
 * <h2>越权防线复用既有查询，不在这里重写</h2>
 * 走 {@link ContactMapper#listForUser} —— 那是联系人列表页自己的查询：归属（{@code created_by}）、
 * 分配（{@code assigned_user_id} / {@code assigned_team_id}）、授权（{@code conversation_access_grants}）、
 * 软删与合并排除，全在那条 SQL 的 {@code where} 里。
 *
 * <p><b>为什么不另写一条「给助手用的」轻量 SQL</b>：那条 SQL 的谓词有二十来行，
 * 照抄一份的代价不是二十行代码，而是「两份授权逻辑」。它们会先在注释里分叉、
 * 再在行为上分叉，而分叉的那一侧<b>不会报错</b>——它只会让某个人在某个入口多看或少看几条联系人。
 * 复用还有一个附带好处：这条查询的投影本来就只有 {@code id / display_name / remark}，
 * 不含任何消息正文，天然满足「候选只带结构化字段」这条合规口径。
 *
 * <h2>排序是有意义的，但时间戳不进口径</h2>
 * SQL 已按最近活动倒序返回（{@code sort_at desc}），而 {@code sort_at} 是查询内部的计算列、
 * 不在 {@code ContactEntity} 上，于是它不会进入候选条目。顺序照旧有意义（提示词按数组顺序呈现），
 * 库里也不多一个「看起来是最后消息时间、其实是 updated_at 兜底」的字段去误导模型。
 */
@Component
public class ContactCandidateProvider {

    /**
     * 候选窗口与检索返回共用同一个上限，理由与会话域逐字相同：
     * 两者不同值会让「查到了但下一轮引用不了」成为一个只在多轮里才暴露的怪现象。
     */
    public static final int LIMIT = 20;

    /** 单条备注进提示词前的字符上限。备注是自由文本，可能很长，而它只是用来消歧的。 */
    static final int REMARK_MAX_CHARS = 60;

    private final ContactMapper contacts;
    private final ContactService contactService;

    public ContactCandidateProvider(ContactMapper contacts, ContactService contactService) {
        this.contacts = contacts;
        this.contactService = contactService;
    }

    /** 提问前注入的候选：最近有往来的联系人。 */
    public ContactCandidates recent(UUID userId) {
        return load(userId, null);
    }

    /** 按关键词检索。空白关键词退化为「最近」，而不是返回空 —— 空结果会被模型读成「你没有联系人」。 */
    public ContactCandidates search(UUID userId, String query) {
        String trimmed = query == null ? "" : query.strip();
        return load(userId, trimmed.isEmpty() ? null : trimmed);
    }

    /** Restore a previous reference window only after rechecking each contact against current owner access. */
    public ContactCandidates refresh(UUID userId, List<String> references) {
        if (userId == null || references == null || references.isEmpty()) {
            return new ContactCandidates(LIMIT, List.of());
        }
        return new ContactCandidates(LIMIT, references.stream().limit(LIMIT)
                .map(ContactCandidates::targetOf)
                .filter(java.util.Objects::nonNull)
                .map(id -> contacts.findAccessibleById(id, userId, ContactService.isCurrentUserAdmin())
                        .map(row -> toItem(row, userId)).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList());
    }

    private ContactCandidates load(UUID userId, String search) {
        if (userId == null) {
            return new ContactCandidates(LIMIT, List.of());
        }
        IPage<ContactEntity> page = contacts.listForUser(new Page<>(1, LIMIT), userId, search, false,
                null, null, ContactService.isCurrentUserAdmin(), null, null);
        List<ContactEntity> rows = page == null || page.getRecords() == null ? List.of() : page.getRecords();
        List<ContactEntity> bounded = rows.size() > LIMIT ? rows.subList(0, LIMIT) : rows;
        return new ContactCandidates(LIMIT, bounded.stream()
                .map(row -> toItem(row, userId))
                .filter(java.util.Objects::nonNull)
                .toList());
    }

    /**
     * 行 → 候选条目。<b>这是个白名单投影</b>：只有下面出现的字段会离开这一层。
     *
     * <p>刻意不投影渠道、未读数、头像这类与会话域重复、且与「要跟谁说句话」这个判断无关的字段 ——
     * 候选越窄，模型越容易挑对，出边界的数据也越少。
     *
     * <p>{@code roleTitle} <b>不在</b>候选里，而不是「取了但丢了」：{@link ContactMapper#listForUser}
     * 的投影本身只有 {@code id / display_name / remark}（外加内部的计算列 {@code sort_at}），
     * 因此 {@code ContactEntity} 上的其余字段在这一行上恒为 {@code null}。往候选里放一个恒空的字段，
     * 模型会读到一堆 {@code null}、下次改代码的人会以为它「有时候有值」—— 角色信息由
     * {@code contact.brief} 经 {@code findAccessibleById} 取（那条查询是全字段）。
     */
    private ContactCandidates.Item toItem(ContactEntity row, UUID userId) {
        if (row == null || row.getId() == null) {
            return null;
        }
        String id = ContactCandidates.idOf(row.getId());
        String remark = blankToNull(row.getRemark());
        String name = firstNonBlank(row.getDisplayName(), remark);
        // 这里的备注是「用来指认这个人是谁」的，不是给模型当事实回答的 —— 所以裸截断：
        // 加「已截断」既不改变指认结果，又会让候选清单变难读（判据见 Texts 的类注释）。
        List<ContactCandidates.Channel> channels = contactService.listAuthorizedChannelProfiles(userId, row.getId())
                .stream()
                .map(profile -> new ContactCandidates.Channel(profile.channelType(), profile.identityValue(),
                        Texts.truncate(profile.displayName(), 60), Texts.truncate(profile.accountLabel(), 60)))
                .toList();
        return new ContactCandidates.Item(id, name == null ? id : name,
                Texts.truncate(remark, REMARK_MAX_CHARS), channels);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
