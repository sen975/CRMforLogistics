# 企业微信统一会话工作区设计

## 状态与优先级

状态：已确认，当前设计真源。

本文建立在 [企业微信会话身份与资料设计](2026-08-24-wecom-conversation-identity-design.md) 之上，后者继续拥有代开发安装实例、ChatData、源会话、参与者、成员资料和权限边界。本文拥有会话列表、联系人直聊聚合、群聊入口、群消息展示合同和 OpenDataFrame 生命周期。

本文取代以下旧 UI 语义：

- 群聊不得进入联系人页面的企业微信会话选择器。
- 同一联系人关联的多个 `DIRECT` 源会话不得显示为多个同名“企业微信会话”。
- 不允许通过并发 `setData()` 实现 latest-wins；官方 frame 的更新必须串行提交。
- 联系人与群聊切换不得创建新的 OpenDataFrame。

## 问题与目标

当前实现暴露四个相互关联的问题：

1. 同一外部联系人与多个员工产生多个 `DIRECT` 源会话，前端按源会话生成多个同名入口。
2. `GROUP` 源会话被放进联系人页面顶部选择器，错误地将群归属于某个联系人。
3. 群消息响应缺少结构化发送者和参与者，前端无法展示真实发言人，也无法提供受控联系人跳转。
4. 联系人 A 直接切换到 B 时，当前实现可能在同一 frame 上并发执行多个 `setData()`。实机表现为企业微信接口 HTTP 200，但 SDK 报 `ww-open-data-frame:session resolve session fail`，消息只剩占位符；`POST /events` 的 403 是错误上报阶段的次生失败。

本轮目标是建立一个统一会话工作区：左侧同时承载联系人和企业微信群，中间区域按所选目标显示联系人混合时间轴或铺满区域的企业微信官方会话组件，并保证整个登录工作区只有一个常驻 OpenDataFrame。

## 边界

### 本轮包含

- 联系人下全部可访问 `DIRECT` 企业微信消息聚合为一个企业微信入口。
- 每个 `GROUP` 源会话按 `sourceConversationId` 独立出现在左侧。
- 群聊使用独立路由和查询合同，不伪造成联系人。
- 群消息返回真实发送者、头像、参与者和可选 CRM 联系人跳转。
- 单 OpenDataFrame 严格串行执行清空和重填，并保证最新导航目标最终可见。
- ChatApp、邮件、电话继续使用联系人混合时间轴。
- 企业微信直聊和群聊占满原时间轴区域，不显示发送输入框；直聊保留“在企业微信中打开”，群聊仅在存在受支持跳转地址时显示该操作。

### 本轮不包含

- 不创建第二个 OpenDataFrame，也不通过销毁重建规避切换问题。
- 不将企业微信群写入 `contacts` 或创建虚假 `contact_identity`。
- 不在前端根据 userid、方向或显示名猜测发送者及跳转目标。
- 不改变企业微信代开发安装实例、ChatData 拉取、成员绑定和已有参与者权限边界。
- 不为无 CRM 联系人的企业员工自动创建联系人。
- 不恢复联系人内的多个企业微信源会话标签，也不恢复混合企业微信正文时间线。

## 证据与约束

企业微信官方会话展示组件文档将 `secret-key` 定义为 ChatData `encrypted_secretkey` 解密后的消息密钥，没有声明一次性使用或单次解析限制。官方文档支持 `setData()` 更新，并建议整个消息列表使用一个 OpenDataFrame，以减少白屏、校验请求和安全频率限制。

官方文档没有声明同一 frame 的并发 `setData()` 是安全的。当前代码从 Promise 串行队列改为并发调用后，实机出现仅在连续联系人切换路径触发的 `resolve session fail`。因此实现必须恢复单写者串行合同，并用真实 SDK 错误详情验证根因；不能把 `secretKey` 缓存直接定性为故障根因。

## 唯一 owner

| 语义 | 唯一 owner | 禁止成为 owner 的层 |
| --- | --- | --- |
| `CONTACT` 与 `WECOM_GROUP` 的可见性、排序、分页和未读 | 后端统一会话查询服务 | React 列表拼接、路由组件 |
| `DIRECT` 聚合与 `GROUP` 独立 | 后端源会话查询合同 | `ThreadPage`、显示文案 |
| 群发送者与参与者身份 | `wecom_parties`、参与关系及后端投影服务 | 前端 userid 规则、方向推断 |
| CRM 联系人跳转资格 | 后端联系人访问控制 | 前端仅凭 `contactId` 拼 URL |
| 当前导航目标与工作区模式 | 前端路由 | OpenDataFrame 内部状态 |
| frame 更新顺序、generation 和错误状态 | 单一 frame controller | 页面组件中的散落 effect |
| `msgid -> secretKey` 内存引用缓存 | viewer hook 的有界缓存 | localStorage、IndexedDB、URL、日志 |

## 后端合同

### 统一会话列表

新增统一列表响应 `ConversationListItem`，采用判别联合：

```text
ConversationListItem = ContactConversationItem | WeComGroupConversationItem

ContactConversationItem
  type: CONTACT
  id: contact UUID
  displayName
  avatarUrl?
  channelTypes[]
  lastMessageAt?
  lastText?
  messageCount
  unreadCount

WeComGroupConversationItem
  type: WECOM_GROUP
  id: sourceConversationId UUID
  providerConversationKey: chatId
  displayName
  avatarUrl?
  participantCount
  lastMessageAt?
  lastText?
  messageCount
  unreadCount
```

服务端先按当前 CRM 用户和绑定的企业微信成员执行访问过滤，再统一排序和分页。群会话只有当前成员是 `OBSERVED` 参与者或当前用户具有既有审计权限时才可见。搜索同时匹配联系人显示名、备注和群名称。前端不得分别请求两个分页列表后自行合并。

### 联系人企业微信直聊

联系人线程查询继续返回该联系人的普通渠道消息。进入企业微信模式时，服务端按联系人可访问的外部联系人 party 找到全部 `DIRECT` 源会话，聚合返回消息引用；不把 `sourceConversationId` 暴露成多个产品入口。

聚合只改变展示入口，不改变源事实。每条消息仍保留原 `sourceConversationId`、`msgid`、发送者和审计链路，以便权限判断和问题定位。

### 群聊详情

群聊使用独立接口按 `sourceConversationId` 查询。响应至少包含：

```text
WeComGroupThread
  sourceConversationId
  providerConversationKey
  displayName
  avatarUrl?
  openClientUrl?
  participants[]
  items[]
  nextCursor?
  messageCount
  threadRevision
```

`items` 只包含该源群会话的消息引用。查询必须在服务端验证当前登录成员的参与关系；不能借用联系人 ID 绕过群访问控制。

### 发送者和参与者

企业微信消息响应增加结构化 `sender`：

```text
WeComPartyView
  partyId: UUID
  partyType: EMPLOYEE | EXTERNAL_CONTACT | ROBOT
  providerPartyId: string
  displayName: string
  avatarUrl?: string
  contactId?: UUID
  contactAccessible: boolean
  isCurrentViewer: boolean
```

群详情的 `participants` 使用同一结构。`displayName` 和 `avatarUrl` 来自 `wecom_parties` 的资料投影；资料不可用时返回结构化不可用状态，UI 可安全回退到脱敏 ID，不伪造昵称。

`contactId` 只有在 party 已映射到真实 CRM 联系人且当前 CRM 用户可访问该联系人时才返回；同时 `contactAccessible=true`。内部员工没有对应 CRM 联系人时只返回姓名和头像，不提供跳转，也不创建联系人。当前登录成员与其他参与者遵守同一访问规则，不由前端增加特殊例外。

群成员资料分为两种来源：客户群详情页可通过已准入的 `externalcontact/groupchat/get` 补全成员快照；该接口不得从通用 ChatData scheduler 对所有 `chatid` 无条件调用。企业内部群没有对应的客户群接口，只能以 ChatData 的 `sender` 和 `receiver_list` 观察到的参与者为准，不能把“未出现在存档消息中的成员”伪造成已知成员。ChatData `sender.type=3` 规范化为 `ROBOT`，机器人消息保留在群源会话中并按非当前成员消息处理，不创建 CRM 联系人。

## 路由与工作区

统一列表使用以下 canonical 路由：

```text
/conversations/contact/:contactId
/conversations/wecom-group/:sourceConversationId
```

旧 `/thread/:contactId` 不能长期形成第二套产品合同。实施时应一次性迁移内部导航和测试；是否保留短期重定向只由部署兼容要求决定，重定向不得拥有独立页面或状态。

`ConversationWorkspace` 位于这两个目标路由之上，拥有稳定的 OpenDataFrame host。路由参数变化只更新工作区目标，不卸载 frame controller。联系人目标展示以下两种模式：

- 普通渠道：ChatApp、邮件、电话及非企业微信消息的混合时间轴，并恢复离开前滚动位置。
- 企业微信渠道：聚合后的直聊消息占满时间轴区域，不显示发送框，底部保留“在企业微信中打开”。

群目标直接进入企业微信全区域模式，不显示联系人顶部渠道选择器，不显示 ChatApp、邮件或电话输入框。联系人直聊可继续使用已验证的 `wxwork://message?username=...` 打开本地企业微信；群聊不得把 `chatId` 填入该联系人协议。群详情只有在后端取得企业微信官方支持且经过白名单校验的群跳转地址时才返回 `openClientUrl`，前端仅在该字段存在时显示“在企业微信中打开”，否则不展示无效按钮。

## 单 OpenDataFrame 生命周期

### 单实例约束

登录后的统一会话工作区最多创建一个 OpenDataFrame。frame 仅在工作区最终卸载、退出登录或 viewer 身份变化时 `dispose()`。联系人、群聊和渠道切换都不得创建第二个 frame。

### 串行更新状态机

frame controller 是 `setData()` 的单一写入者，状态为：

```text
IDLE -> CLEARING -> PREPARING -> FILLING -> READY
                         \-> FAILED
```

每次导航产生单调递增 generation。目标更新执行：

1. 记录最新目标和 generation。
2. 中止尚未进入 frame 的旧网络准备请求。
3. 等待当前 `setData()` 完成或达到受控超时；不得同时调用第二个 `setData()`。
4. 串行提交 `{ msgList: [] }`。
5. 为最新目标准备可展示消息引用。
6. 如果 generation 已过期，丢弃结果并转到最新目标。
7. 串行提交最新目标的 `{ msgList }`。
8. 只在 `handleUpdated` 或 `setData()` 成功完成且 generation 仍为最新时进入 `READY`。

快速 A -> B -> A 时，中间目标允许在尚未写入 frame 前被合并掉，但已经开始的官方 `setData()` 不得被并发覆盖。超时不会触发新 frame；controller 进入 `FAILED`，显示重试入口，并在用户重试时从清空步骤重新串行执行。

### 消息引用缓存

缓存键继续使用 viewer token、目标作用域和 `msgid`，值只保存 `{msgid, secretKey}`。缓存必须有容量和 TTL 上界，并在 viewer token 变化、退出登录或明确失效时清空。缓存不得进入持久化存储、URL、日志或错误快照。

缓存命中不代表跳过权限判断：目标列表和消息 ID 必须来自本次已授权线程响应。若真实 SDK 错误详情证明某类 secretKey 已失效，应按明确错误码淘汰对应引用并重新读取 session，不能无条件关闭整个缓存。

## 群聊界面

左侧群条目使用企业微信图标、群名称、最后消息时间、未读数和安全摘要。群条目不可拖拽参与联系人合并。

群聊中间区域包含：

- 紧凑群头部：群名称和参与人数。
- 可横向滚动或折叠的参与者区域：头像、姓名和类型提示。
- 铺满剩余区域的官方会话展示组件。
- 可选底部“在企业微信中打开”操作；只有群详情返回受支持的 `openClientUrl` 时显示。

群消息气泡外层展示发送者头像和姓名，正文仍由 `ww-open-message` 渲染。发送者有 `contactAccessible=true` 时，姓名和头像作为联系人链接；点击后导航到 `/conversations/contact/:contactId`。无链接资格时保持普通文本，不出现不可用按钮。

## 错误处理与观测

SDK 的 `error`、模板组件 `binderror` 和 frame 更新失败必须转换为结构化前端错误：

```text
stage: frame-clear | session-prepare | frame-fill | component-resolve
generation
targetType: CONTACT | WECOM_GROUP
targetIdDigest
sdkErrCode?
sdkErrMsgCategory?
viewerSessionId?
```

禁止记录消息正文、完整目标 ID、viewer token 或 secretKey。浏览器控制台中企业微信请求 HTTP 200 但 `result` 表示失败时，诊断必须保留脱敏 `errcode` 和失败阶段，不能只记录 Axios 的 HTTP status。

`POST /events` 是审计辅助链路，不得替代用户可见错误。每个 generation、viewerSessionId 和错误类别最多上报一次；缓存命中但没有新 viewerSessionId 时不发送伪造事件。审计接口对重复上报应幂等接受或明确返回已记录状态，不得让次生 403 淹没原始 SDK 错误。

登录态错误 `42006`、`42003`、`40029` 或 `Missing open sid` 按官方合同引导重新扫码登录；其他解析错误保留当前 frame，允许受控重试。

## 测试与验收

### 后端专项测试

- 统一列表按时间排序并混合返回 `CONTACT` 与 `WECOM_GROUP`，分页无重复或遗漏。
- 普通用户只能看到自己参与的群；非参与者访问群详情返回 403。
- 同一联系人关联多个 `DIRECT` 源会话时只生成一个产品入口，但消息保持各自源会话审计字段。
- 两个不同群保持两个独立列表项和线程，不归入任何联系人。
- 群消息 sender 与 participants 返回真实姓名、头像和 party 类型。
- 只有真实、可访问的 CRM 联系人才返回可跳转 `contactId`；无 CRM 联系人的员工不可跳转。
- 群未读、最后消息和 cursor 分页只统计本群源会话。

### 前端专项测试

- 联系人左侧条目和群左侧条目进入不同 canonical 路由；群条目不可拖拽合并。
- 同一联系人不再出现两个企业微信会话标签。
- A -> B、A -> B -> A、A -> 群 -> B 和快速连续切换全程只创建一个 frame。
- 任意时刻最多一个 `setData()` pending；调用顺序必须是清空后填充最新目标。
- 旧网络结果、旧 generation 的错误和旧 `handleUpdated` 不得覆盖当前目标。
- 历史消息追加后使用同一 frame 串行更新，不丢失当前滚动语义。
- 群发送者显示姓名和头像；只有 `contactAccessible=true` 时可点击跳转。
- `events` 同一错误只上报一次；审计 403 不替代原始 SDK 错误。
- loading、空态、部分消息不可读、登录过期、SDK 解析失败和超时均有明确状态，不出现无文字占位符。

### 构建与实机门禁

本地专项和全量测试通过后才能生成 Jar 和前端 dist。前端必须用真实 Chrome 在桌面和移动宽度检查布局、切换和无重叠状态。服务器部署后至少执行：

1. 核对入口资产 hash 与目标构建一致。
2. 使用同一登录成员完成 A -> B、A -> B -> A、A -> 群 -> B。
3. 检查 Network 中 CRM session 请求、企业微信 `openChatSession` 的脱敏 result 和 `/events` 去重结果。
4. 验证两个直聊聚合、两个群独立、群参与者和联系人跳转。
5. 验证邮件、ChatApp 和电话混合时间轴及原滚动位置未回归。

任何一个场景出现第二个 frame、并发 `setData()`、群被归入联系人、发送者错误、越权跳转、永久占位符或 `resolve session fail`，均停止打包发布。

## 实施分解边界

实施必须按以下依赖顺序进入独立任务：

1. 后端统一会话列表和群详情合同。
2. 后端 sender、participants 与联系人访问投影。
3. 前端 canonical 路由和统一左侧列表。
4. 联系人 `DIRECT` 聚合及群独立工作区。
5. 单 frame controller 串行状态机和错误去重。
6. 专项测试、全量回归、真实 Chrome 和服务器实机验收。

每个任务先建立失败测试，再实现最小闭环。不得在 UI 任务中补造后端缺失语义，也不得在 frame 修复中顺手改变 ChatData、成员绑定或权限模型。
