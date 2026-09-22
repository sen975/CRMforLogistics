package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.dto.response.ContactTagResponse;
import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.entity.ContactAiLabelEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity;
import com.crmforlogistics.messagecenter.entity.ContactProfileVersionEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryMapper;
import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import com.crmforlogistics.messagecenter.service.contactmemory.ContactMemoryModels;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * 联系人简报的来源：一次授权查询 + 一次记忆投影，产出一份有界的 {@link ContactBrief}。
 *
 * <h2>两道防线各司其职，这里不重复任何一条</h2>
 * <ul>
 *   <li><b>归属/权限</b>：走 {@link ContactMapper#findAccessibleById} —— 与联系人详情接口
 *       同一个方法（归属人 / 管理员 / 团队分配 / 授权表）。这里不重写谓词，理由同
 *       {@link ContactCandidateProvider} 的类注释。</li>
 *   <li><b>记忆的可见性</b>：记忆各表以 {@code owner_user_id} 归属，那个值是联系人的
 *       {@code created_by}。因此这里问的是 {@code userId.equals(createdBy)}，
 *       而不是「能不能看到联系人」—— 两者是不同的判据，混起来会让管理员看见别人的画像。</li>
 * </ul>
 *
 * <h2>取数只走 {@code listStableContext}</h2>
 * 它返回画像 + 事实 + 标签 + 话题四节。<b>消息原文与通话转写在这条路上根本取不到</b> ——
 * 这是比「记得别取」更强的保证，理由见 {@link ContactBrief} 的类注释。
 *
 * <h2>各节上限是常量而不是配置项</h2>
 * 总量有一个硬天花板：整份结果会被序列化进一条 observation，而
 * {@code AssistantPromptBuilder} 对单条 observation 有字符上限（超出即截断并明写「已截断」）。
 * 若把各节上限做成配置，调大它就会越过那个天花板，症状是模型拿到半份数据却不知道被截断过 ——
 * 这种「配置能制造的错误」不该开出来。上限值由 `ContactBriefProviderTest` 用最坏情况钉住。
 */
@Component
public class ContactBriefProvider {

    /** 画像摘要字符上限。 */
    static final int PROFILE_MAX_CHARS = 400;

    /** 结构化事实：条数与单字段字符上限。 */
    static final int FACT_LIMIT = 8;
    static final int FACT_CATEGORY_MAX_CHARS = 20;
    static final int FACT_VALUE_MAX_CHARS = 90;

    /** AI 标签与人工标签：各 10 条、每条 24 字。 */
    static final int LABEL_LIMIT = 10;
    static final int TAG_LIMIT = 10;
    static final int NAMED_MAX_CHARS = 24;

    /** 话题摘要：条数与字符上限。 */
    static final int TOPIC_LIMIT = 4;
    static final int TOPIC_TITLE_MAX_CHARS = 40;
    static final int TOPIC_SUMMARY_MAX_CHARS = 120;

    /** 备注进简报的字符上限（联系人自己的自由文本，可能很长，而它只是背景）。 */
    static final int REMARK_MAX_CHARS = 200;

    /**
     * 显示名的字符上限。
     *
     * <p>看起来多余（名字能有多长？），但它<b>必须</b>有：{@code contacts.display_name} 是自由文本，
     * 可以是一整句「张江物流（上海）国际货运代理有限公司-东南亚线-王总」。
     * 没有上限时预算就是假的 —— 一份简报的规模不能由用户怎么填字段决定。
     */
    static final int NAME_MAX_CHARS = 60;

    /**
     * 一次记忆查询用的条数上限。
     *
     * <p>刻意取各节上限里的<b>最大值</b>：{@code listStableContext} 的四条 select 共用一个 limit，
     * 取小了会让某一节被无声地掐短（那时既没有截断标记、也没有任何断言会失败）。
     * 真正的分节裁剪发生在下面——那里每节按自己的上限收口。
     */
    static final int MEMORY_QUERY_LIMIT = 10;

    private final ContactMapper contacts;
    private final ContactMemoryMapper memory;
    private final ContactTagMapper humanTags;

    public ContactBriefProvider(ContactMapper contacts, ContactMemoryMapper memory, ContactTagMapper humanTags) {
        this.contacts = contacts;
        this.memory = memory;
        this.humanTags = humanTags;
    }

    /**
     * 生成简报。
     *
     * @throws IllegalArgumentException 联系人不存在<b>或</b>当前用户无权查看。
     *         两类原因刻意不区分：措辞由工具层统一收敛，避免把「这条 id 是存在的」漏给另一个账号。
     */
    public ContactBrief brief(UUID userId, UUID contactId) {
        if (userId == null || contactId == null) {
            throw new IllegalArgumentException("Contact not found");
        }
        ContactEntity contact = contacts.findAccessibleById(contactId, userId, ContactService.isCurrentUserAdmin())
                .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + contactId));

        String reference = ContactCandidates.idOf(contactId);
        String remark = truncate(blankToNull(contact.getRemark()), REMARK_MAX_CHARS);
        String name = truncate(firstNonBlank(contact.getDisplayName(), remark), NAME_MAX_CHARS);
        String label = name == null ? reference : name;
        String roleTitle = truncate(blankToNull(contact.getRoleTitle()), NAMED_MAX_CHARS);

        if (!userId.equals(contact.getCreatedBy())) {
            // 记忆以联系人的 created_by 为归属人。看不到就明说看不到，而不是返回四节空数据 ——
            // 「他没有画像」与「画像对你看不见」是两件不同的事，后者用户是可以找人问的。
            return new ContactBrief(reference, label, roleTitle, remark, false,
                    null, List.of(), List.of(), List.of(), List.of());
        }

        ContactMemoryModels.StableContext stable = memory.listStableContext(userId, contactId, MEMORY_QUERY_LIMIT);
        List<ContactTagResponse> tags = humanTags == null
                ? List.of()
                : humanTags.findActiveByContactIdAndOwner(contactId, userId);

        return new ContactBrief(reference, label, roleTitle, remark, true,
                profileOf(stable),
                factsOf(stable),
                labelsOf(stable),
                tagsOf(tags),
                topicsOf(stable));
    }

    // ---------- 各节投影 ----------

    private static String profileOf(ContactMemoryModels.StableContext stable) {
        if (stable == null || stable.currentProfile() == null) {
            return null;
        }
        ContactProfileVersionEntity profile = stable.currentProfile();
        return truncate(blankToNull(profile.getContent()), PROFILE_MAX_CHARS);
    }

    private static List<ContactBrief.Fact> factsOf(ContactMemoryModels.StableContext stable) {
        if (stable == null || stable.activeFacts() == null) {
            return List.of();
        }
        List<ContactBrief.Fact> facts = new ArrayList<>();
        for (ContactMemoryFactEntity fact : stable.activeFacts()) {
            if (fact == null || facts.size() >= FACT_LIMIT) {
                continue;
            }
            // displayValue 是给人看的写法，normalizedValue 是规范化写法；两者是<b>同一条事实</b>
            // 的两个字段，因此退回后者不是编造。两者皆空的事实直接跳过（留着它没有信息量）。
            String value = firstNonBlank(fact.getDisplayValue(), fact.getNormalizedValue());
            if (value == null) {
                continue;
            }
            facts.add(new ContactBrief.Fact(
                    truncate(blankToNull(fact.getCategory()), FACT_CATEGORY_MAX_CHARS),
                    truncate(value, FACT_VALUE_MAX_CHARS)));
        }
        return List.copyOf(facts);
    }

    private static List<String> labelsOf(ContactMemoryModels.StableContext stable) {
        if (stable == null || stable.activeLabels() == null) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (ContactAiLabelEntity label : stable.activeLabels()) {
            if (label == null || names.size() >= LABEL_LIMIT) {
                continue;
            }
            String name = firstNonBlank(label.getDisplayName(), label.getNormalizedName());
            if (name != null) {
                names.add(truncate(name, NAMED_MAX_CHARS));
            }
        }
        return List.copyOf(names);
    }

    private static List<String> tagsOf(List<ContactTagResponse> tags) {
        if (tags == null) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (ContactTagResponse tag : tags) {
            if (tag == null || names.size() >= TAG_LIMIT) {
                continue;
            }
            String name = blankToNull(tag.name());
            if (name != null) {
                names.add(truncate(name, NAMED_MAX_CHARS));
            }
        }
        return List.copyOf(names);
    }

    private static List<ContactBrief.Topic> topicsOf(ContactMemoryModels.StableContext stable) {
        if (stable == null || stable.topics() == null) {
            return List.of();
        }
        List<ContactBrief.Topic> topics = new ArrayList<>();
        for (AiTopicEntity topic : stable.topics()) {
            if (topic == null || topics.size() >= TOPIC_LIMIT) {
                continue;
            }
            String title = truncate(blankToNull(topic.getTitle()), TOPIC_TITLE_MAX_CHARS);
            // 人工确认过的小结优先于模型小结：会前准备要的是「上次到底谈成什么」，
            // 而 confirmed 这一节正是有人核对过的那份。两者都没有时，只留标题也够用。
            String summary = truncate(firstNonBlank(topic.getConfirmedSummary(), topic.getAiSummary()),
                    TOPIC_SUMMARY_MAX_CHARS);
            if (title == null && summary == null) {
                continue;
            }
            topics.add(new ContactBrief.Topic(title, summary));
        }
        return List.copyOf(topics);
    }

    // ---------- 小工具 ----------

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

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }
}
