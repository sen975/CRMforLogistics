# 邮件收件人解析修正（撤掉归属校验）

## 问题与边界

`specs/2026-09-28-email-persist-before-smtp-design.md` 第 1 条原要求「收件人只接受
当前用户在所选邮件账号下已有联系人绑定的邮箱」。实现把它落成
`ContactIdentityMapper.findEmailRecipientByOwner`：

```sql
where c.owner_user_id = #{ownerId}::uuid
  and ci.channel_type = 'email'
  and ci.identity_scope = #{channelAccountId}::text
```

这两个条件在真实数据上**恒为假**，导致 2026-09-28 起任何收件人都发不出邮件
（实测该 owner 名下可通过校验的联系人 = **0**）。原因不是数据脏，而是**判据选错了字段**：

1. **`contacts.owner_user_id` 是本项目从未接线的字段。** 全项目 0 处
   `contact.setOwnerUserId(...)`；建联系人的 5 个写入点（`EmailSyncService:464,480`、
   `ChannelAddressBookService:95,156,229`）**全部只写 `created_by`**。它只被
   `V48__backfill_user_channel_owners.sql` 一次性回填过（实测 `contacts` 53 行中 **41 行 NULL**）。
   项目判定「归我所有」的既有判据是 `created_by`；`ContactMemoryStateMapper` 的注释
   甚至写明「`contacts.owner_user_id` may be null」。
2. **`ci.identity_scope` 对 email 存在两种历史形态。** 老代码写字面量 `'email'`，
   新代码写 `channel_accounts.id`（`EmailSyncService:313`，已是当前路径）。实测 13 条
   email 身份中 **10 条是老形态**。
3. **「取地址」与「校验收件人」是两次独立查询、两套条件**：`OutboundMessageService.resolve`
   看 `created_by`/会话可见性、**不看 scope**；`EmailSendService.requireRecipient`
   看 `owner_user_id`、**只认 scope = 账号**。⇒ 第一步必然成功、第二步必然失败，
   而失败文案「收件邮箱不属于你通讯录中已有的联系人」把锅甩给了用户。

## 已采用的设计（2026-09-28 小森决策）

**邮件发送不看归属**：只要有一个合法邮箱地址就能发。

- **人工入口 · API 层**（`POST /send/email`，`MessageController.sendEmail` 直接收 `to`）：任意合法地址都可发，含通讯录外的人。
- **人工入口 · 前端 UI 层**（`frontend/src/components/SendForm.tsx`，被 `SendPage` 与 `ThreadPage` 复用）：**必须先选联系人** —— 无 `contact` 时直接渲染「选择一个联系人开始发送消息」。收件人字段 `toSelect()`（第 396-399 行）只在联系人**没有**该渠道身份时退化成可手填的 `Input`；**有**身份时是 `Select`，只能从该联系人的身份里挑。⇒ **目前没有「不选联系人、直接填地址发信」的正经入口**，「任意地址」在 UI 上只剩那条隐形兜底路。
  - ⇒ 因此本次修复的**主收益是让「选联系人发信」能通过校验**：库里 13 条 email 身份有 10 条是老 scope `'email'`，旧校验只认发信账号 UUID ⇒ 必然拒绝，这才是界面发不出信的原因。**不是**为了支持给陌生地址发信。
- **AI 入口**（`send_email` 工具）：**仍然只能引用已有联系人**（`contactRef` → 服务端解析地址）。
  收件人不是模型参数这条不改 —— 模型编不出地址，幻觉风险无从发生。
- 服务端只做格式校验；**不创建联系人**。

## 实现

### 1. 复用查询：`ContactIdentityMapper.findSendableEmailRecipient(accountId, normalizedEmail)`

替换原 `findEmailRecipientByOwner(ownerId, accountId, normalizedEmail)`。`left join contacts`
以容纳孤立身份；**不看 `owner_user_id`，也不看 `created_by`**；scope 命中三种形态
（本账号 id / 历史 `'email'` / 建表默认 `'global'`），指向**其他**邮箱账号的身份不命中；
身份与联系人需未删除、未合并。排序让本账号 scope 优先。

### 2. 解析：`EmailSendService.resolveRecipient(accountId, normalizedEmail)`

替换 `requireRecipient(...)`。查询命中就复用；未命中则新建一条**孤立身份**
（`contact_id` 为空、`source='manual'`、scope = 本账号 id、`is_primary=false`），
用既有 `insertIfAbsent`（`on conflict do nothing`）保证并发安全，再回查一次。
**刻意不创建联系人**：发一封信不该在通讯录里凭空多出一个人。这个地址日后被同步或
手工加为联系人时，`EmailSyncService` 的既有认领逻辑（`linkToNewContact`）会复用这条
孤立身份，不会重复建档。

### 3. 错误码收窄

`EMAIL_RECIPIENT_NOT_FOUND` 现在只有「不是单一合法邮箱地址」一个来源
（多收件人、邮件组、解析失败），文案同步改掉。AI 工具层的映射文案也从
「收件邮箱不属于你通讯录中已有的联系人」改为说明格式约束。

## 反转的测试（原语义 vs 现语义）

| 测试 | 原来保护什么 | 现在改成 |
|---|---|---|
| `EmailRecipientBoundaryTest.unknownRecipientIsRejectedBeforeAnyPersistenceOrSmtp` | 未知地址被拒、不落库不调 SMTP | 未知地址落成孤立身份后继续（失败点后移到持久化） |
| `EmailRecipientBoundaryTest.identityWithoutContactCannotAuthorizeSending` | 孤立身份不能发 | 已有孤立身份被复用、不新建第二条 |
| `EmailRecipientDatabaseTest.unknownForeignAndOrphanedIdentitiesNeverStartPersistenceOrSmtp` | 未知/他人/孤立/跨账号/异渠道 5 类一律发出不出去 | 这 5 类**全部发送成功**，且**没有创建任何 contact** |
| `EmailRecipientDatabaseTest.onlyAnActiveEmailIdentityLinkedToTheOwnersContactInTheSelectedAccountMatches` | 跨 owner 不可复用 | 只保留「死身份（删除/合并）不可复用」；归属不再参与 |
| `EmailSendServiceTest.persistedOutboundWithoutLinkedContactIsRejected` | 无联系人身份落库被拒 | 允许落库，只是不记 topic activity |

反掉的只是**归属**这一层；「身份/联系人已删除或已合并 ⇒ 不复用」这层仍然钉着
（`onlyALiveEmailIdentityInTheSelectedAccountScopeIsReused` 覆盖）。

新增 `EmailRecipientDatabaseTest.historicalLiteralEmailScopeIsStillReusable`：
把「scope = 字面量 `'email'`」这个真实历史形态显式钉住 —— 库里 10/13 条是这个形态，
不兼容就等于所有人发不出邮件。

## 验收（2026-09-28 实测）

用 `/tmp/mc-javac.sh` 绕开 pom 的 `<testIncludes>` 过滤编译，`mvn surefire:test` 运行：

| 测试类 | 结果 |
|---|---|
| `EmailSendServiceTest` | 17 / 0 / 0 |
| `EmailRecipientBoundaryTest` | 8 / 0 / 0 |
| `EmailOwnerIsolationTest` | 4 / 0 / 0 |
| `EmailRecipientDatabaseTest`（真库 + 真 SMTP） | 4 / 0 / 0 |
| `OutboundMessageServiceTest` | 13 / 0 / 0 |
| `MessageSendAssistantToolsTest` | 13 / 0 / 0 |
| `EmailSyncServiceTest` / `EmailSyncSchedulerOwnerTest` / `EmailSubmissionLeaseKeeperTest` | 13 / 1 / 2，全 0 失败 |
| `EmailControllerTest` | 7 / 0 / 0（重编陈旧 class 后） |

## 未做（明确留口）

- **`contacts.owner_user_id` 仍是死字段**（0 处写入）。本次**不再依赖它**，所以不再阻塞发信；
  但它仍会误导下一个拿它做过滤的人。要接上就得在 5 个建联系人处补写入 + 写迁移回填。
- **历史 email 身份的 scope 未回填**。读侧已兼容，不阻塞；回填干净后才能把
  `findSendableEmailRecipient` 的 scope 条件收紧回等值匹配。
- **重复联系人未合并**（`3332099198@qq.com` 挂在两个 active「守望」上）。读侧按
  scope 优先选一条，不影响发信；要不要合并是产品判断。
- **人工入口的 HTTP 验收未做**：会真的发出邮件，需人工确认。
- **前端没有「不选联系人、直接填地址」的入口 —— 2026-09-28 决策：保留现状**。后端 API 层支持
  任意地址，但 `SendForm` 要求先选联系人，且联系人**有**该渠道身份时收件人是 `Select`（只能从它
  的身份里挑）。决定：能力留着，既不补前端入口，也不回收后端分支。
  - 后果一（可接受）：孤立身份分支线上几乎不产生，属「有测试覆盖、无实际流量」的兜底。
  - 后果二（**已知语义缝隙，本次未处理**）：唯一会真正建孤立身份的路径是「选一个**没有**邮箱身份
    的联系人 → 收件人字段退化成可手填 `Input` → 手填任意地址」。而 `POST /send/email` 的请求体
    只有 `to`/`subject`/`body`、**不带 `contactId`** ⇒ 后端不知道这封信是在谁的页面上发的，建出的
    孤立身份**不指向那个联系人**，这封信不会记在他名下。若要收口：前端在该联系人没有该渠道身份时
    不给手填 `Input`（改为提示先补邮箱），或给接口加可选 `contactId` 把身份挂过去。
