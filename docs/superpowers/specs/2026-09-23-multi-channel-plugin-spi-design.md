# 多渠道可插拔设计 —— 「接入钉钉/飞书 = 新增一个包」

> 2026-09-23 · 设计稿（未实施）
> 触发问题：「把邮件、企业微信、chatapp、电话的相关代码抽出来当成独立的包，后续只要引用即可有相关服务，想拆分的时候去掉引用就行，可能吗？」

## 0. 结论先行

**可能，但只做到了一维。**

这个代码库里已经有一条做对的样板：**账号/凭证维度**的 `ChannelType` SPI —— 加一个渠道 = 加一个 `@Service implements ChannelType`，内核零改动，`ChannelTypeRegistry` 用 Spring 集合注入自动收集，key 冲突启动即失败。

但**消息维度没有**。今天接入一个钉钉/飞书，除了自己的包，还必须回头改内核 **34 处**（跨渠道层的渠道字面量），并复制一整套入站落库与出站派发逻辑。

所以本方案的落点是：**把 `ChannelType` 那个已经验证过的模式，从「账号」复制到「消息」（入站 / 出站 / 呈现）**，让「一个渠道」= 一个包 + 一组端口实现。

---

## 1. 目标与非目标

### 目标

1. 新增渠道（钉钉/飞书）的成本 = **只写一个新包**，不修改任何内核类。
2. 删除一个渠道包（去掉引用）后，系统仍能编译、启动、运行，只是该渠道不可用。
3. 上述两条**可被测试证明**，而不是靠口头约定。

### 非目标（明确不做）

| 不做 | 理由 |
|---|---|
| 拆 Maven module / 独立 gradle 子项目 | 包级隔离 + ArchUnit 门禁已足够表达边界；拆 module 会增加构建复杂度却换不来运行期收益 |
| 拆成独立部署的服务 | `messages`/`conversations`/`contacts` 是内核共享表，物理拆分立刻变成分布式事务，是另一个量级的工程 |
| 拆数据库 / 给渠道独立 schema | 同上。渠道不拥有表，只拥有「怎么和厂商通信」 |
| 前端渠道组件跟着拆 | 那是路由/懒加载问题，与后端可插拔性无关 |

---

## 2. 现状实测

### 2.1 已经做到的（这条是样板，不用重做）

| 项 | 证据 |
|---|---|
| SPI 接口存在且方法设计合理 | `service/channel/ChannelType.java` —— 4 个必填方法（`key`/`credentialFields`/`normalizeIdentifier`/`syncAccount`）+ 5 个 `default` 方法，渠道只需实现自己关心的部分 |
| 装配是自动的 | `ChannelTypeRegistry(List<ChannelType>)` —— Spring 集合注入，构造时建不可变索引，`key`/别名冲突**启动即抛**（`ChannelTypeRegistry:62-68`） |
| 内核服务确实零渠道知识 | `ChannelAccountService` 全文只有 3 处 `channelTypes.require(...)`（100/143/175 行），**无 `switch`、无渠道字面量** |
| 有书面判据 | `ChannelType` 的类注释直接写明「新增渠道 = 新增一个实现类，核心零改动」；`ChannelAccountService` 注释记录改造前有四份渠道清单（`CREDENTIAL_KEYS`、`switch` 分派、`normalizeIdentifier` 的 if-else、写死 chatapp 判断），现已全部消除 |

**但是：只有 2 个实现** —— `ChatAppChannelType`（`key=chatapp`，别名 `whatsapp`）和 `EmailChannelType`。**企业微信、电话、以及 WhatsApp 的入驻侧都不在这张注册表上**（WeCom 有自己的 `wecom_installations`，WhatsApp 有自己的 `whatsapp_provider_scopes`）。

**而且实现类放错了包**：`ChatAppChannelType` 住在内核编排层 `service/channel/` 里，却 `import channel.chatapp.ChatAppMessageSyncService` 与 `service.whatsapp.template.WhatsAppProviderScopeService`。这直接让「删掉渠道包即可摘除」不成立 —— 删了 `channel/chatapp`，`ChatAppChannelType` 就编不过。**渠道定义（接口实现）应当住在渠道包里，内核只留接口 + 注册表。**

### 2.2 没做到的（这是本方案要解决的全部）

| # | 卡点 | 实测数字 |
|---|---|---|
| 1 | **消息出站是「假通用」** | outbox 骨架住在 `service/chatapp/outbox/`；`jobType` 写死 `"chatapp_send"`（`MessageSendApplicationService:134`）；消费者按字面量判断 `"chatapp_send".equals(job.getJobType())`（`MessageOutboxWorker:198`）；依赖渠道专属的 `ChatAppAccountResolver`；错误码一律 `CHATAPP_*`（159/163/166/174 行） |
| 2 | **入站落库无统一入口** | `new MessageEntity()` 散在 **9 处**：`ChatAppWebhookProjector`×2、`WeComMessageProjector`×3、`EmailSyncService`×1、`EmailSendService`×1、`ChatAppBroadcastMessageProjector`×1、`MessageSendApplicationService`×1。每个渠道各自组装消息行、自己找会话、自己建联系人身份 |
| 3 | **跨渠道层硬编码渠道名 34 处 / 10 文件** | `service/aitopic/AiTopicService` 7、`service/message/ThreadService` 6、`service/aitopic/AiTopicInputService` 5、`service/message/MessageQueryService` 4、`service/contact/ChannelAddressBookService` 3、`service/contact/ContactService` 2、`service/channel/ChatAppCapabilityService` 2、`mapper/ChannelAccountMapper` 2、`infrastructure/cams/ChatAppAccountCredentialsResolver` 2、`dto/response/ConversationListItemResponse` 1 |
| 4 | **HTTP 面按渠道平铺** | 40 个 Controller 里 **16 个**挂在渠道专属路径下（`/api/whatsapp/*` 5、`/api/admin/whatsapp/*` 5、`/api/v1/wecom/*` 4、`/api/v1/chatapp/*` 1、`/api/admin/chatapp/*` 1） |
| 5 | **渠道绕编排层直连数据层** | `channel/` 包内 **15 个文件 / 51 处** `import ...mapper.*` / `...entity.*`（email 6 文件、chatapp 8、wecom 1） |
| 6 | **渠道之间横向依赖** | 渠道包越出本板块 import 其他渠道 service：**18 个文件**，集中在 `chatapp → service.whatsapp.template`（单 `AliyunChatAppTemplateGateway` 就 18 处）；另有 `email → service.wecom` |
| 7 | **前端渠道类型是硬编码联合类型** | `types.ts:47` `'chatapp'｜'email'｜'phone'`、`types.ts:775` `'chatapp'｜'email'`、`types.ts:864` `'chatapp'｜'email'｜'phone'｜'wecom'`；`endpoints.ts` 有大量 `/v1/wecom/**`、`/whatsapp/**`、`/chatapp/**` 专用端点 |

### 2.3 三个典型症状

```
症状 A（出站）：加飞书要复制 outbox
  MessageOutboxWorker ──"chatapp_send".equals(jobType)──▶ ChatAppOutboundGateway
  想加飞书：要么改 worker 的 if，要么把 outbox 复制一份 —— 两条路都要动内核

症状 B（入站）：每个渠道自己写消息表
  EmailSyncService      ──new MessageEntity()──▶ messageMapper.insertWithSequence
  WeComMessageProjector ──new MessageEntity()──▶ messages.insertWithSequence
  ChatAppWebhookProjector ─new MessageEntity()─▶ messageMapper.insertWithSequence
  （找会话、建 identity、算 seq、加未读 —— 三段逻辑各写一遍）

症状 C（呈现）：内核提名具体渠道
  ThreadService:197   if ("wecom".equalsIgnoreCase(channelType) || channelType == null) ← 默认渠道硬编码
  MessageQueryService:138-141  switch: email→Email / chatapp→ChatApp / wecom→WeCom / whatsapp→WhatsApp
  ChannelAddressBookService:320  List.of("chatapp", "email", "phone") ← 白名单
```

---

## 3. 目标形态

### 3.1 一句话

**渠道实现端口；内核提供落库。**

两条依赖都指向中间层（端口由内核定义，渠道实现），内核不反向 import 任何渠道实现。

### 3.2 分工原则（本方案的核心判断）

| 维度 | 谁负责 | 形态 |
|---|---|---|
| 账号 / 凭证 / 同步 | 渠道实现 | **端口**（`ChannelType`，已有） |
| 入站消息**怎么拿到** | 渠道自己 | **不抽端口** —— 推(webhook)/拉(轮询) 天然不同，统一只会造出扭曲的接口 |
| 入站消息**怎么落库** | 内核提供 | **服务**（`InboundMessageIngestService`，新增） |
| 出站消息 | 渠道实现 | **端口**（`OutboundSender`，新增） |
| 展示名 / 默认渠道 / 能力 | 渠道实现 | **并入 `ChannelType` 的 `default` 方法**（不新开接口） |

**「入站不抽端口」是刻意的**：现有三个渠道的入站模式完全不同 —— email 是定时拉取、WeCom 是 webhook 推 + 定时拉、chatapp 是 webhook + 轮询。硬抽一个 `poll()` 会让 webhook 型渠道实现一个空方法，抽一个 `onEvent()` 又要求内核理解各厂商事件格式。**真正重复的不是「怎么收到」，是「收到之后怎么落库」** —— 那才是内核该收口的地方。

### 3.3 端口清单

```java
// ① 已有 —— 账号维度
public interface ChannelType {
    String key();
    Set<String> aliases()               { return Set.of(); }
    Set<String> credentialFields();
    Set<String> secretFields()          { return Set.of(); }
    String normalizeIdentifier(String identifier);
    void assertBindableFromSettings()   { }
    void assertScope(Map<String,String> credentials) { }
    void bindScope(ChannelAccountEntity account)     { }
    Object syncAccount(UUID ownerId, UUID accountId) throws Exception;

    // ② 新增 —— 呈现维度（default 方法，不强迫旧渠道实现）
    default String displayName()        { return key(); }      // 替代 MessageQueryService 的 switch
    default boolean isDefaultChannel()  { return false; }      // 替代 ThreadService 写死的 "wecom"
    default boolean supportsAddressBook(){ return false; }     // 替代 ChannelAddressBookService 白名单
    default boolean supportsOutbound()  { return false; }      // 决定是否注册出站能力
}

// ③ 新增 —— 出站维度
public interface OutboundSender {
    /** 写入 outbox_jobs.job_type 的值，全注册表唯一，冲突启动即失败。 */
    String jobType();

    /** 提交一条出站消息。失败语义沿用现有两个异常：
     *  RetryableException（可重试）/ SubmissionUnknownException（结果未知，不重试）。 */
    void submit(OutboundRequest request) throws Exception;
}
```

`OutboundSenderRegistry` 与 `ChannelTypeRegistry` 同形：`List<OutboundSender>` 集合注入，构造期建不可变索引，`jobType` 冲突抛 `IllegalStateException`。

### 3.4 内核提供的服务

```java
// ④ 新增 —— 入站落库的唯一入口
@Service
public class InboundMessageIngestService {
    /** 渠道把厂商事件翻译成中立信封后调这里；会话查找、联系人匹配、
     *  消息写入（含 seq / 去重 / 未读计数）全在本方法内完成。 */
    @Transactional
    public IngestResult accept(InboundEnvelope envelope);
}

public record InboundEnvelope(
        String channelType,
        UUID channelAccountId,
        String peerIdentifier,      // 对方标识（手机号/邮箱/企微 id）
        String peerDisplayName,
        String direction,           // inbound / outbound-echo
        String messageKind,
        String bodyText,
        String metadataJsonb,
        Instant occurredAt,
        String providerMessageId    // 幂等键
) { }
```

`MessageOutboxWorker` 改为查表分派：

```java
// 现在
if (accountResolver != null && "chatapp_send".equals(job.getJobType()) && ...) { ... }

// 之后
OutboundSender sender = senders.require(job.getJobType());   // 未注册 ⇒ jobType 直接判死
sender.submit(request);
```

---

## 4. 迁移路线

四步，按「收益 / 成本」排序。**每步独立可合入、独立可验收**，不要求一次做完。

### 第 0 步 —— 门禁与归包（零风险，先做）

**做什么**
1. 在 `ArchitectureBoundaryTest` 增加一条规则：**渠道包之间不得互相依赖**（`channel.chatapp..` 不得 import `channel.wecom..` / `channel.whatsapp..` 等）。
2. 把 `channel/chatapp/AliyunWhatsAppCallbackGateway` 挪到 `channel/whatsapp/` —— 它是 WhatsApp 的东西却住在 chatapp 包，是横向依赖的**命名根源**，零抽象成本。
3. 把 `ChatAppChannelType` / `EmailChannelType` 从 `service/channel/` 挪回各自渠道包。内核只留 `ChannelType` 接口 + `ChannelTypeRegistry`。
4. 处理 `chatapp → service.whatsapp.template`（18 个文件里的主要部分）：`service.whatsapp.template` 里的东西本质是「CAMS 供应商模板能力」，按归属挪进 `channel/chatapp/template/`（一个供应商一个包），横向依赖随之消失。

**为什么先做**：后面每一步都要靠门禁防止回退；且第 4 项是纯归包、零抽象 —— 它把「渠道间耦合」这个最容易被当成借口的东西先消掉。

**验收**：新门禁绿；全量测试绿；`git grep 'import com\..*channel\.\(wecom\|whatsapp\)' -- channel/chatapp` 为 0。

### 第 1 步 —— 出站上移（收益最大）

**做什么**
1. `service/chatapp/outbox/*` → `service/message/outbox/*`（`MessageSendApplicationService`、`MessageOutboxScheduler`、`MessageOutboxWorker`）。
2. 引入 `OutboundSender` + `OutboundSenderRegistry`；`ChatAppOutboundGateway` 变成一个实现，`jobType()` 返回 `"chatapp_send"`（**保持原值**，不做数据迁移）。
3. `MessageSendApplicationService` 的 `jobType` 由 `channelType` 查表得出，而不是写死；错误码从 `CHATAPP_*` 改为中性码（如 `OUTBOUND_KIND_UNSUPPORTED`），渠道专属错误码由 sender 自己抛。
4. `MessageOutboxWorker` 去掉 `"chatapp_send".equals(...)` 分支，改查表；`ChatAppAccountResolver` 的注入改为由 sender 自己持有。

**为什么收益最大**：出站是唯一有「通用骨架被单渠道私有化」的地方 —— 骨架已经写好了（事务内写 messages + outbox_jobs，commit 后唤醒轮询，退避重试，租约 fencing），只是被挂在 chatapp 名下。上移之后，**加飞书出站 = 实现一个 `OutboundSender`**。

**验收**：新增一个测试用假 sender（`jobType="fake_send"`），断言 `MessageOutboxWorker` 能派发到它，**且 worker 源码零改动**。

### 第 2 步 —— 入站收口

**做什么**
1. 抽 `InboundMessageIngestService` + `InboundEnvelope`；把三段重复逻辑（找/建 `conversations`、匹配/建 `contact_identities`、写 `messages` 含 seq 与未读）收进去。
2. **逐渠道替换** 9 处 `new MessageEntity()`。顺序建议：先 `WeComMessageProjector`（3 处，最独立），再 `ChatAppWebhookProjector`（2 处），再 email 两处（`EmailSyncService` / `EmailSendService` 内各有一份重复的 `resolveOrCreateIdentity`，正好一并收口），最后 `ChatAppBroadcastMessageProjector`。
3. 渠道包内不再出现 `MessageMapper` / `ConversationMapper` / `ContactIdentityMapper`。

**验收**：`git grep 'MessageMapper\|new MessageEntity' -- channel/` 为 0（channel 包只保留厂商调用与翻译）。

### 第 3 步 —— 呈现收口

**做什么**：把 34 处字面量逐条换成端口查询。
- `MessageQueryService:138-141` 的 4-case switch → `registry.require(type).displayName()`
- `ThreadService` 6 处 `"wecom"`（尤其 197 行的默认值）→ `registry.defaultChannel()`；注意 106/140/238/315/352 那几处是**「企微群聊数据」专属逻辑**（走 `weComChatDataMessageMapper`），它们不该被泛化，应当**下沉到企微渠道包**，由渠道提供自己的读取扩展
- `ChannelAddressBookService` 白名单与 `whatsapp → chatapp` 归一 → `supportsAddressBook()` + `registry.require(...)` 的别名解析
- `service/aitopic` 12 处（`AiTopicService` 7、`AiTopicInputService` 5）→ 同上
- `mapper/ChannelAccountMapper:257/296` 的 `.in(List.of("chatapp","whatsapp"))` → 按别名集合推导
- `infrastructure/cams/ChatAppAccountCredentialsResolver` 2 处 → 移进 chatapp 渠道包（它本来就是 CAMS 专属）

**验收**：2.2 表格第 3 行的 34 处降为 0（跑一遍同一统计脚本）。

### 第 4 步 —— 前端（收益较低，可最后做）

**做什么**
1. 后端暴露 `GET /api/channels`（注册表导出：`key` / `displayName` / `supports*`）。
2. `types.ts` 三处硬编码联合类型改为 `string` + 运行时校验（或由生成）。
3. 渠道专属端点**不强行收敛** —— `/api/v1/wecom/viewer`（企微会话查看器）这类有真实专属语义的端点保留；只把「同一件事按渠道分叉」的端点（如 `send/text`）收进 `/api/channels/{type}/...`。

**验收**：前端不再出现写死的渠道联合类型；新增渠道时前端**无需改动**即可在渠道列表里看到它（专属页面另说）。

---

## 5. 必须先拍板的两件事

### 5.1 WeCom / WhatsApp / 电话要不要纳入统一账号模型？

现状：`ChannelType` 只有 2 个实现。WeCom 用 `wecom_installations`（安装/授权模型），WhatsApp 用 `whatsapp_provider_scopes`（CAMS 范围），电话不在这个抽象里。

| 选项 | 代价 | 结果 |
|---|---|---|
| **A. 允许渠道自带账号子模型**（端口留一个可选扩展点，渠道可声明「我的账号不走 `channel_accounts`」） | 低 | 快，但「插拔」语义不完整 —— 账号侧仍有两套世界 |
| **B. 先统一账号模型，再把三个渠道接进来** | 高 | 真插拔，但要迁移 `wecom_installations` / `whatsapp_provider_scopes` |

**建议 A。** 理由：钉钉（`corpId` + `agentId` + `appKey/appSecret`）与飞书（`appId` + `appSecret` + 事件订阅）的账号模型**大概率也各异**。强行统一会造出一张「万能字段表」加一堆 `assertScope` 分支 —— 那正是 `ChannelAccountService` 改造前被消灭的东西，不该换个形式复活。

### 5.2 入站到底抽不抽端口？

**建议不抽。** 理由见 §3.2：三个渠道的入站模式（拉 / 推 / 推+拉）本质不同，统一接口必然变形。**只统一落库（`InboundMessageIngestService`）**，让每个渠道自己决定怎么拿到事件。

如果将来钉钉与飞书都是「webhook + 验签 + 拉详情」这一种形状，**那时再抽** —— 两个真实样本才够抽象出正确的接口，现在只有 0 个。

---

## 6. 验收判据：怎么证明「加一个包就行」

写一个 **`ChannelPluginConformanceTest`**：构造一个假渠道（`FakeChannel implements ChannelType, OutboundSender`，放在测试专用的独立包里），断言：

1. `/api/channels` 返回列表里**自动出现** `fake`（注册表收集，内核零改动）
2. 用 `fake` 走一遍账号绑定 → 成功（`ChannelAccountService` 零改动）
3. 用 `fake` 走一遍出站 → outbox job 被 worker 认到并派发（`MessageOutboxWorker` 零改动）
4. 用 `fake` 走一遍入站 → `InboundMessageIngestService.accept(...)` 落库成功，会话与身份被正确关联
5. 消息 DTO 里 `channelDisplayName` 显示 `fake` 声明的名字（`MessageQueryService` 零改动）

**这个测试本身就是「可插拔性」的定义**。它通过 = 加一个包成立；它失败 = 说明内核又有地方被渠道知识污染了 —— 它同时是回归守卫。

---

## 7. 风险与取舍

| 风险 | 说明 | 缓解 |
|---|---|---|
| 第 2 步（入站收口）是四步里最重的 | 9 处落库点各有历史差异（email 的 `identityScope`、chatapp 的 peer 归一化、wecom 的群聊语义），收口时容易把差异揉成条件分支 | 逐渠道替换，每次只动一个渠道；`InboundEnvelope` 只承载中立字段，渠道差异留在渠道侧的翻译器里 |
| 第 3 步 `ThreadService` 的 6 处不是同一种东西 | 1 处是「默认渠道」（该泛化），5 处是「企微群聊数据读取」（该下沉，不该泛化） | 分清楚再动，见第 3 步说明 |
| 门禁可能被绕过 | 已有 `CHANNEL_AWARE_SERVICE_PACKAGES` 白名单，历史上就是靠它放行的 | 新增白名单条目必须写明理由（类注释已有这条约定）；`service/channel` 已因「职责就是协调渠道」在内，属合理 |
| 假渠道测试变成形式主义 | 如果 `FakeChannel` 用了一堆测试专用后门，它证明不了生产可插拔 | `FakeChannel` 只允许用生产 SPI 与 Spring 装配，禁止 `@MockBean` 替换内核服务 |

---

## 8. 与现有约定的衔接

- 本方案**不改变**「业务域 → 基础设施」的既有单向门禁（`ArchitectureBoundaryTest` 规则一），只在其上增加「渠道之间不得互相依赖」与「渠道不得直连内核 Mapper」（第 2 步后）。
- `ChannelType` 的 `default` 方法策略沿用既有先例（`aliases`/`secretFields`/`assert*` 共 5 个 default），**新增渠道仍然只需要写一个类**，不因本方案变成「实现四个接口」。
- 建议第 3 步的收口**不要**用 `FreezingArchRule` 冻基线：34 处分布在 10 个文件，按文件分批改完即可，冻基线只会留一个语义模糊的终点（`ArchitectureBoundaryTest` 类注释已记录过这个教训）。
