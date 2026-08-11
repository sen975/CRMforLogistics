# React SPA 迁移前端分析

日期: 2026-08-04
数据源: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java` -- `pageHtml()` 方法（第 779--2989 行）

## 1. 页面布局

单页应用渲染两种顶层状态：

**状态 A: 企业微信登录页** (`id="wecomLoginScreen"`)
- 全视口居中卡片，品牌名"统一消息中心"
- 通过企业微信 JS-SDK `ww.createWWLoginPanel()` 渲染的 OAuth2 二维码登录面板
- 状态文本行 (`id="wecomLoginStatus"`) 和重试按钮 (`id="wecomLoginRetry"`)
- 在 `LOCAL_DEV_MODE` 下，此屏幕隐藏，直接显示主界面

**状态 B: 主界面（工作区）** (`id="shell"`)
- CSS Grid 布局，3 列 2 行
- 列宽：`320px / minmax(430px, 1fr) / var(--detail-width)`，其中 `--detail-width` 默认为 `360px`
- 详情面板收起时，`--detail-width` 缩小为 `48px`
- 第 1 行：顶栏，横跨 3 列
- 第 2 行：三个面板

```
+------------------------------------------------------------------------------+
|  第 1 行: 顶栏 (64px, 横跨 3 列)                                                |
|  [品牌 | 联系人数量] [线程标题 | 同步按钮 | 刷新按钮] [空白区域]                      |
+---------------------+---------------------------+----------------------------+
| 第 2 行, 第 1 列      | 第 2 行, 第 2 列            | 第 2 行, 第 3 列              |
| 联系人面板             | 消息面板                    | 详情面板                      |
| +------------------+ | +-----------------------+ | +------------------------+ |
| | 搜索输入框        | | | 消息列表（可滚动）       | | | [详情折叠按钮]          | |
| +------------------+ | | 消息/通话记录卡片       | | | 联系人详情 /            | |
| | 联系人列表        | | +-----------------------+ | | 消息详情 /              | |
| | 联系人条目        | | | 发送器 (260px)         | | | 通话记录详情              | |
| | （可滚动）         | | | 渠道标签 + 表单         | | | （可滚动）               | |
| +------------------+ | +-----------------------+ | +------------------------+ |
+---------------------+---------------------------+----------------------------+
```

**模态框（Portal）：**
- `#profileModal` -- 编辑联系人昵称/标签（420px 宽模态框）
- `#previewImageModal` -- 全屏图片预览，带关闭按钮
- `#wecomOpenModal` -- iframe 模态框，用于企业微信会话详情（最大 960x720）
- `#toast` -- 右下角固定定位通知提示

**响应式：** 在 `max-width: 980px` 时，grid 折叠为单列堆叠布局。每个面板 `max-height: 58vh`。

## 2. 组件树

### 2.1 企业微信登录页

- **渲染：** 带二维码占位、状态文本和重试按钮的品牌登录卡片。
- **状态：**
  - **加载二维码：** 状态文本显示"正在准备二维码"/"正在加载企业微信登录组件"，面板中显示加载占位。
  - **二维码就绪：** 企业微信 SDK 将二维码 `<iframe>` 渲染到 `#wwLoginPanel`。
  - **扫码中：** 状态显示"请在企业微信中确认登录"。
  - **登录中：** 状态显示"正在进入消息中心"。
  - **错误：** 状态显示错误信息，重试按钮可见。

### 2.2 主界面（主布局容器）

`#notifyBtn` 上的 `onclick` 处理器调用 `enableNotifications()`。

### 2.3 顶栏 (`.workspace-topbar`)

- 左侧区域 (`.workspace-section`)：品牌名"统一消息中心" + 联系人数量 (`#contactCount`，文本如"0 个联系人") + 通知按钮。
- 中间区域 (`.workspace-center`)：
  - 线程标题 (`#threadTitle`)：显示选中联系人的 `displayName` 或"选择联系人"。
  - 编辑资料按钮 (`#editProfileBtn`，未选联系人时隐藏)，带编辑图标。
  - 副标题 (`#threadSub`)：联系人渠道摘要或占位文本"邮件和 ChatApp 按时间穿插显示"。
  - 右侧操作：三个同步按钮 (`#syncEmailBtn`、`#syncChatBtn`、`#syncWeComBtn`) + 刷新按钮 (`#refreshBtn`)。
- 右侧区域 (`.workspace-detail`)：空白（用于平衡折叠/展开详情面板的对齐）。

### 2.4 联系人面板

- **搜索栏：** `<input id="searchInput">` 占位文本"搜索联系人、邮箱、号码"。每次 `input` 事件触发 `renderContacts()`。过滤通过 `JSON.stringify(contact).toLowerCase().includes(term)` 实现。
- **联系人列表** (`#contacts`)：可滚动的 `.contact` div 列表。

#### 联系人条目 (`.contact`)
- **渲染：**
  - 头像按钮，带渠道类型图标（邮件/ChatApp/企业微信图标，通过 CSS 伪元素实现）。
  - 显示名称 (`.contact-name`)。
  - 联系方式摘要 (`.contact-points`) -- 拼接 `contact.points` 的值。
  - 最后消息文本 (`.contact-last`)。
  - 未读指示器：1 条未读显示红点 (`.contact-unread-dot`)，>1 条显示红色数字徽章 (`.contact-unread-count`)。
- **状态：**
  - **选中** (`.contact.active`)：当 `contact.id === state.selectedPointId` 时显示蓝色背景。
  - **未读**：当 `state.unreadByContact[contact.id] > 0` 时显示红点或数字徽章。
  - **空：** 无联系人时显示"暂无联系人"。
- **交互：**
  - `click` 行：调用 `selectContact(id)`。
  - `dragstart`：设置 `dataTransfer`，携带联系人 `id`。
  - `dragover`：`preventDefault()` 允许拖放。
  - `drop`：调用合并 API，传入源和目标 ID。
- **头像点击** (`.avatar[data-avatar]`)：无注册处理器，纯装饰。

### 2.5 消息面板

- **消息容器** (`#thread`)：可滚动区域，显示消息和通话记录。
- **空状态：** "左侧选择一个联系人"。
- **发送器** (`#composer`)：底部 260px 固定高度区域，始终渲染，根据联系人选择显示/隐藏。

#### 消息行 (`.message-row`)
- **渲染：**
  - 头像图标（渠道相关：邮件信封、ChatApp 对话气泡、企业微信点阵）。
  - 消息气泡 (`.msg`)：
    - 标题 (`.msg-title`)（如有）。
    - 正文文本 (`.msg-text`)（如有）。
    - 媒体预览 (`.msg-media`)（如有 `mediaUrl` 或 `objectKey`）。
  - 元信息行 (`.msg-meta-line`)：渠道标签、时间戳、状态图标、"打开附件"按钮。
- **方向变体：**
  - `inbound`：头像在左，气泡在左。
  - `outbound` (`.message-row.outbound`)：头像在右，气泡在右，绿色背景 (`.msg.outbound`)。
- **状态：**
  - **选中** (`.msg.active`)：当 `m.id === state.selectedMessageId` 时显示蓝色边框。
  - **有媒体** (`.msg.has-media`)：宽度限制为 `min(320px, 100%)`。
- **交互：**
  - `click` `.msg[data-id]`：调用 `selectMessage(id)`。
  - `click` `.call-card[data-call-record-id]`：调用 `openCallRecordDetail(id)`。
  - `click` `[data-open-media]`：调用 `openAttachment()`。

#### 媒体预览 (`.msg-media`)
- **渲染：**
  - 加载占位 ("正在拉取图片")，加载完成后隐藏。
  - **图片类型：** `<img>` 带 `onload`/`onerror` 处理器。点击触发 `openImagePreview()`。
  - **视频类型：** `<video>` 带播放控件、`onloadedmetadata`/`onerror`。
  - **其他：** 文件名显示，带文件图标。
- **状态：**
  - `.loaded`（已加载）：隐藏加载占位。
  - `.failed`（媒体错误）：显示"附件暂不可用"消息，隐藏预览元素。

#### 通话记录卡片 (`.call-card`)
- **渲染：**
  - 电话图标（CSS 旋转边框样式）。
  - 通话方向（"呼入电话"/"呼出电话"）和状态徽章（"排队中"/"转录中"/"已完成"/"失败"）。
  - 电话号码标签、通话时长。
  - 元信息行："电话记录" + 时间戳。
- **交互：** `click`/`keydown Enter/Space` 调用 `openCallRecordDetail(id)`。

#### 发送器 (`.composer`)
- 未选联系人时为空。
- 渠道标签：根据 `contact.channels` 动态生成（email、chatapp、wecom），外加"电话记录"标签。
- 内容面板 (`#sendPanel`) 根据 `state.selectedChannel` 重新渲染。

##### 发送器：邮件标签
- **渲染：** 收件人 `<select>`（从联系人的邮件联系方式填充）、主题 `<input>`、正文 `<textarea>`、"发送邮件"按钮。
- **交互：**
  - `sendEmail` onclick 主按钮。
  - 账号选择下拉变更更新 `state.selectedPointByChannel`。

##### 发送器：ChatApp 标签
- 子模式标签：text、template、image、video、document，通过 `state.selectedMode` 切换。
- **文本模式：**
  - 收件人 `<select>`、消息 `<textarea>`、表情面板 (`.emoji-panel` -- 通过 `.open` 类显示/隐藏)、工具栏（表情/模板/图片/视频/文档图标按钮）、"发送 WhatsApp"按钮。
  - 不带 Ctrl 的 Enter 键发送消息。
  - 表情按钮切换表情面板；点击表情将其插入光标位置。
- **模板模式：**
  - 收件人 `<select>`、模板 `<select>`、动态占位字段 (`#tplFields`，3 列网格)、模式切换按钮、"发送模板"按钮。
- **图片/视频/文档模式：**
  - 收件人 `<select>`、标题 `<textarea>`、文件拖放区（带隐藏文件 `<input>`）、模式切换按钮、"发送附件"按钮。
  - 点击文件拖放区打开文件浏览器。

##### 发送器：企业微信标签
- **渲染：** 企业微信账号显示、"打开企业微信会话"按钮、查看器容器 (`#wecomViewerContainer`)。
- **交互：** 按钮点击通过 API 加载企业微信会话，然后：
  - `LOCAL_DEV_MODE`：渲染本地示例消息。
  - 生产模式：使用消息列表初始化企业微信 OpenData frame 工厂。

##### 发送器：通话记录标签
- **渲染：** 文件 `<input>`（仅 MP3）、方向 `<select>`（呼入/呼出）、datetime-local `<input>`、电话号码 `<select>`、进度条、状态文本、"上传录音"按钮。
- **交互：** 按钮点击触发 `uploadCallRecord()`，使用 `XMLHttpRequest` 和 `FormData`（非 `fetch`），支持上传进度跟踪。

### 2.6 详情面板

- **头部：** 折叠切换按钮 (`#detailToggleBtn`)。
- **内容** (`#detail`)：可滚动，根据上下文重新渲染。

#### 详情：联系人视图
- **渲染：**
  - 区域标题"联系人资料"。
  - 昵称（`contact.remark` 或"未设置"）。
  - 标签（`.tag-pill` 条目或"未设置"）。
  - 账号列表：每个账号渲染为 `.account-item`，包含值、渠道标签和拆分按钮。
- **状态：**
  - **空：** "点击一个联系人查看资料"。
- **交互：**
  - 拆分按钮 (`.account-split-button`)：调用 `splitAccount(pointId)`。

#### 详情：消息视图
- **渲染：** 键值对：渠道、方向、时间戳、发件人、收件人、状态、标题、正文文本、原始 JSON。
- **激活方式：** 点击消息面板中的消息气泡。

#### 详情：通话记录视图
- **渲染：**
  - 标题（含方向和状态徽章）。
  - 更新错误行。
  - KV 对：电话号码、发生时间、时长、原始文件名。
  - 音频播放器 (`<audio id="callAudio">`)。
  - 音频错误消息区。
  - 失败原因（如转录失败）。
  - 完整转录文本。
  - 时间段。
  - 修订历史。
  - 修订文本区 + "保存修订"按钮。
  - "重新转录"按钮（如失败且可重试）。
- **轮询：** 对于 `queued` 或 `processing` 状态，每 3 秒轮询一次，带指数退避（最多 30 秒），连续失败 5 次后停止。
- **音频会话：** 每 240 秒续期 (`CALL_AUDIO_RENEW_MS`)。音频错误时，尝试通过续期恢复一次。
- **可见性处理：** 标签页隐藏时停止音频续期；标签页可见时续期。

### 2.7 模态框

#### 资料模态框 (`#profileModal`)
- **渲染：** 昵称输入框、标签输入框、保存/取消按钮、保存状态指示器。
- **状态：**
  - **有修改：** 保存状态显示"未保存"。
  - **保存中：** "保存中"，按钮禁用。
  - **错误：** "保存失败"。
- **交互：**
  - 取消/关闭：重置修改标记，隐藏模态框。
  - 保存：调用 `POST /api/contact-groups/profile`。
  - 在任一输入框中按 Enter 键触发保存。
  - 点击遮罩关闭模态框。

#### 图片预览模态框 (`#previewImageModal`)
- **渲染：** 全视口遮罩，居中图片和关闭按钮。
- **交互：** 关闭按钮或 Escape 键或点击遮罩隐藏模态框并清空 `src`。

#### 企业微信打开模态框 (`#wecomOpenModal`)
- **渲染：** iframe 加载企业微信会话详情的 HTTPS URL。
- **交互：** 点击遮罩或 Escape 键清空 iframe 并隐藏模态框。

### 2.8 提示通知

- **渲染：** 右下角固定定位提示，2.6 秒后自动隐藏。
- **用法：** 全局调用，用于成功/错误通知。

## 3. API 接口全景

### 3.1 `api(url, options)` -- 标准 fetch 封装
返回 `response.json()`。非 2xx 响应抛出 `{status, code, message}` 格式错误。

### 3.2 `viewerApi(url, options)` -- 带认证的 fetch 封装
与 `api()` 相同，但注入 `X-WeCom-Viewer-Auth` 头（从 `currentWeComAuth().viewerAuthToken` 获取）。返回解析后的 JSON（204 返回 `{}`）。非 2xx 响应抛出错误。

### 3.3 接口列表

| # | 方法 | 路径 | 触发方 | 请求体 | 使用的响应字段 | 更新目标 |
|---|------|------|--------|--------|---------------|----------|
| 1 | GET | `/api/channel-capabilities` | `enterMessageCenter()` | -- | `[{channel, ...}]` 存入 `state.capabilities` | 启用渠道相关 UI |
| 2 | GET | `/api/templates` | `enterMessageCenter()`、`refreshTemplates()`、`syncChatApp()` | -- | `[{templateCode, templateName, languageCode, placeholders[]}]` | 存入 `state.templates`，用于模板下拉和占位字段 |
| 3 | GET | `/api/contacts` | `loadContacts()` | -- | `[{id, displayName, remark, tags[], channels[], points[{id, channel, value, type, label}], lastTime, messageCount, lastText, lastDirection, lastChannel, source}]` | 存入 `state.contacts`，驱动联系人列表、顶栏、详情、发送器 |
| 4 | GET | `/api/v1/contacts/{id}/timeline?limit=10&cursor=` | `loadThread()`、`loadOlderThreadMessages()` | -- (viewerApi) | `{items[{type, sortId, occurredAt, payload:{id, channel, direction, timestamp, title, text, summary, status, mediaType, mediaUrl, objectKey, fileName, from, to, state, version, durationSeconds, phonePointId, transcriptionState}}], nextCursor, itemCount, threadRevision}` | 渲染消息面板消息 |
| 5 | GET | `/api/messages?id={id}` | `selectMessage()` | -- | `{id, channel, direction, timestamp, from, to, status, title, bodyText, text, raw}` | 在详情面板中渲染消息详情 |
| 6 | GET | `/api/media?id={id}` | `openAttachment()`、`mediaPreviewHtml()` | -- | 二进制 blob，带 `Content-Type` 和 `Content-Disposition` 头 | 下载或内嵌打开附件 |
| 7 | POST | `/api/send/email` | `sendEmail()` | `{to, subject, body}` | -- | 清空表单，刷新全部 |
| 8 | POST | `/api/send/chatapp` | `sendChatText()`、`sendTemplate()` | `{mode:"text"|"template", to, text?, templateCode?, templateName?, languageCode?, templateParamsJson?, clientRequestId}` | -- | 清空表单，刷新全部 |
| 9 | POST | `/api/send/chatapp-media` | `sendMedia()` | FormData: `{to, mediaType, caption, clientRequestId, file}` | -- | 刷新全部 |
| 10 | POST | `/api/contact-groups/merge` | 联系人列表拖放 | `{primaryPointId, mergedPointId}` | -- | 刷新选中联系人视图 |
| 11 | POST | `/api/contact-groups/split` | 详情面板拆分按钮 | `{primaryPointId, pointToSplit}` | -- | 刷新选中联系人视图 |
| 12 | POST | `/api/contact-groups/profile` | `saveContactProfile()` | `{contactPointId, nickname, tags}` | -- | 刷新全部，关闭模态框 |
| 13 | POST | `/api/sync/email` | `syncEmail()` 通过 `runSync()` | `{}` | `{saved, skipped}` | 提示通知，刷新全部 |
| 14 | POST | `/api/sync/chatapp` | `syncChatApp()` 通过 `runSync()` | `{}` | `{saved, updated, pages, durationMillis, templatesSaved, mediaCached, mediaQueued, mediaFailed, mediaFailures[{reason}]}` | 提示通知，刷新模板和全部 |
| 15 | POST | `/api/v1/wecom/conversation-view/sync` | `syncWeCom()` | `{}` (viewerApi) | `{stored, skipped}` | 提示通知，刷新全部 |
| 16 | POST | `/api/v1/wecom/login/attempts` | `initWeComLogin()` | `{}` | `{loginType, appId, redirectUri, state}` | 存入 `state.wecomLoginAttempt`，用于 WWLoginPanel 配置 |
| 17 | POST | `/api/v1/wecom/login/exchange` | `completeWeComLogin()` | `{code, state}` | `{viewerAuthToken, expiresIn}` | 存入 `state.wecomAuth` 和 `state.wecomAuthExpiresAt` |
| 18 | GET | `/api/v1/wecom/conversation-view/sessions/{id}` | `loadWeComViewer()` | -- (viewerApi) | `{viewerSessionId, messages[{msgid, secretKey}]}` | 用于渲染企业微信 OpenData frame |
| 19 | POST | `/api/v1/wecom/conversation-view/sessions` | `loadWeComViewer()` | `{contactPointId, viewerAuthToken}` | `{viewerSessionId}` | 会话 ID 用于获取详情 |
| 20 | GET | `/api/v1/wecom/js-sdk-config?url=` | `loadWeComViewer()` | -- (viewerApi) | `{corpId, agentId, configSignature, agentConfigSignature, jsApiList}` | 用于 `ww.register()` 和 `ww.initOpenData()` |
| 21 | POST | `/api/v1/wecom/conversation-view/events` | `reportWeComViewerEvent()` | `{eventType, viewerSessionId}` | -- | 即发即弃（仅捕获错误） |
| 22 | GET | `/api/v1/call-records/{id}` | `openCallRecordDetail()`、`refreshCallRecordDetail()` | -- (viewerApi) | `{id, direction, phonePointId, occurredAt, version, transcription:{state, result:{originalText, segments[{startSeconds, endSeconds, text}]}, error:{message, retryable}}, revisions[{id, text, editedAt, editedBy}], currentRevisionId, audio:{durationSeconds, originalFileName}}` | 渲染通话详情面板 |
| 23 | POST | `/api/v1/call-records/{id}/retry` | `retryCallRecord()` | `{clientRequestId}` (viewerApi) | 更新后的通话记录对象 | 重新渲染通话详情 |
| 24 | PATCH | `/api/v1/call-records/{id}/transcript` | `reviseCallRecord()` | `{text, expectedVersion}` (viewerApi) | 更新后的通话记录对象 | 重新渲染通话详情，刷新消息面板 |
| 25 | POST | `/api/v1/call-records/{id}/audio-sessions` | `renewCallAudioSession()` | -- (viewerApi) | -- | 设置 `state.callAudioSessionReady = true`，设置音频 `src` |
| 26 | GET | `/api/v1/call-records/{id}/audio` | Audio 元素 `src` 属性 | -- (viewerApi) | 音频二进制数据 | 在 `<audio>` 元素中播放 |
| 27 | POST | `/api/v1/contacts/{contactId}/call-records` | `uploadCallRecord()` (XHR) | FormData: `{direction, occurredAt, clientRequestId, phonePointId?, file}` (viewerApi) | `{state}` | 刷新消息面板 |
| 28 | GET | `/events` | `connectEvents()` (EventSource) | -- (SSE) | 见第 4 节 | 触发后台刷新 |

注：`POST /api/v1/wecom/conversation-view/sessions` 返回 `{viewerSessionId}`，然后用于 `GET /api/v1/wecom/conversation-view/sessions/{id}`。POST 响应格式为 `{viewerSessionId}`。

## 4. SSE 事件

`EventSource` 连接到 `GET /events`。

### 4.1 连接生命周期
- 在 `enterMessageCenter()` 调用的 `connectEvents()` 中建立。
- 每次 `connectEvents()` 调用时关闭并重新创建。
- 服务器在连接时发送 `: connected\n\n`，每 25 秒发送 `: heartbeat\n\n`（两者都是 SSE 注释，浏览器 `EventSource` 忽略）。
- 服务器发送错误（IOException）导致客户端从服务器端列表移除。

### 4.2 事件类型（客户端处理方式）

#### `message` 事件（默认/未命名）
- **服务器端触发：**
  - `publish(UnifiedMessage)` -- 发送 `data: <UnifiedMessage JSON>\n\n`
  - `publish(CallRecordEvent)` -- 发送 `data: {"type":"callRecord","callRecordId":"...","contactAnchorPointId":"...","state":"...","version":...}\n\n`
- **客户端处理器：** `state.eventSource.onmessage = () => refreshInBackground(true)`
- **效果：** 调用 `refreshAll(true)`，静默加载联系人，如果选中了联系人则重新加载消息面板。静默模式意味着：如果联系人渲染 key 未变，跳过重新渲染；如果用户正在滚动，推迟刷新 (`pendingSilentRefresh = true`)。

#### `templates-changed` 事件（命名事件）
- **服务器端触发：** `publishTemplatesChanged(int count)`
- **服务器负载：** `event: templates-changed\ndata: {"count": N}\n\n`
- **客户端处理器：** `state.eventSource.addEventListener('templates-changed', async () => { await refreshTemplates(); })`
- **效果：** 重新获取 `GET /api/templates`，存入 `state.templates`。如果当前在 ChatApp 模板发送器上，重新渲染发送器。

**假设：** `templates-changed` 事件负载中的 `count` 字段客户端未读取；仅接收但忽略。客户端无条件重新获取全部模板。

## 5. 数据流

### 5.1 中央状态对象

所有客户端状态存储在单个 `state` 对象中（非响应式存储 -- 手动修改，随后命令式 DOM 更新）：

```javascript
const state = {
  contacts: [],              // 来自 GET /api/contacts 的数组
  templates: [],             // 来自 GET /api/templates 的数组
  capabilities: {},          // Map<channel, object>，来自 GET /api/channel-capabilities
  selectedPointId: '',       // 当前选中的联系人 ID
  selectedMessageId: '',     // 当前选中的消息 ID（用于详情面板）
  selectedCallRecordId: '',  // 当前选中的通话记录 ID
  selectedChannel: '',       // 当前发送器标签 ('email'|'chatapp'|'wecom'|'callRecord')
  selectedMode: 'text',      // ChatApp 子模式 ('text'|'template'|'image'|'video'|'document')
  lastKey: '',               // 上次联系人变更 key（用于检测新消息）
  contactsRenderKey: '',     // 联系人渲染缓存 key（防止不必要的重新渲染）
  threadRenderKeyByContact: {}, // Map<contactId, renderKey> -- 消息面板渲染缓存
  threadPages: {},           // Map<contactId, {items[], nextCursor, isLoadingOlder, hasLoadedInitial, pageItemCount, threadRevision}>
  threadPageAccessOrder: [], // 联系人 ID 的 LRU 列表（用于页面缓存淘汰）
  threadLoadSeqByContact: {}, // 每个联系人的单调递增序号（丢弃过期加载）
  threadTouchY: 0,           // 触摸 Y 位置（用于检测上滑加载更早消息）
  detailCollapsed: false,    // 详情面板折叠状态
  profileDirty: false,       // 资料模态框是否有未保存修改
  profileSavedPointId: '',   // 刚保存的联系人 ID（用于跳过过期刷新）
  profileSavedTimer: null,   // 定时器句柄（当前代码中未使用）
  selectedPointByChannel: {}, // Map<channel, pointId> -- 记住每个渠道选中了哪个账号
  contactSnapshots: {},      // Map<contactId, {lastTime, messageCount, lastDirection, lastChannel}>
  unreadByContact: {},       // Map<contactId, count> -- 未读消息数
  isUserScrolling: false,    // 是否有滚动面正在被用户滚动
  pendingSilentRefresh: false, // 是否有因滚动而推迟的刷新
  wecomLoginAttempt: null,   // 来自 POST /api/v1/wecom/login/attempts 的登录尝试对象
  wecomAuth: null,           // 来自 POST /api/v1/wecom/login/exchange 的认证对象
  wecomAuthExpiresAt: 0,     // 过期时间戳
  viewerReloginPromise: null, // 重新登录流程的去重
  messageCenterInitialized: false, // 事件处理器是否已绑定
  eventSource: null,         // EventSource 实例
  refreshTimer: null,        // setInterval 句柄（用于定期刷新）
  callDetail: null,          // 当前通话记录详情对象
  callDetailGeneration: 0,   // 单调递增代数（使过期详情加载失效）
  callDetailPollTimer: null, // setTimeout 句柄（用于通话详情轮询）
  callDetailPollFailures: 0, // 连续失败计数
  callAudioRenewTimer: null, // setInterval 句柄（用于音频会话续期）
  callAudioRecovered: false, // 音频恢复是否已尝试
  callAudioSessionReady: false, // 是否存在有效音频会话
};
```

### 5.2 数据流模式

**模式 1: 服务器 → State → DOM（联系人）**
```
GET /api/contacts → state.contacts → renderContacts() → innerHTML of #contacts + #contactCount
```
去重：`contactRenderKey()` 计算所有联系人字段的哈希。如果静默刷新且 key 未变，跳过重新渲染但仍更新 `contactSnapshots`。如果 key 变了，重新渲染完整列表。

**模式 2: 服务器 → State → DOM（消息面板消息）**
```
GET /api/v1/contacts/{id}/timeline → state.threadPages[id] → renderThreadMessages() → innerHTML of #thread
```
去重：`threadRenderKey()` 计算联系人头部 + 所有消息字段的哈希。如果 `keepScroll` 为 true 且 key 未变，跳过重新渲染但保持滚动位置。同时 `state.threadLoadSeqByContact[id]` 丢弃过期加载的响应（竞态条件）。

**模式 3: 服务器 → State → DOM（消息详情）**
```
GET /api/messages?id={id} → (不存入 state) → renderMessageDetail(m) → innerHTML of #detail
```
注：消息详情不存入 state -- 每次点击时重新获取并直接渲染。

**模式 4: 用户操作 → API → 刷新全部 → DOM**
```
POST /api/send/email → refreshAll(false) → loadContacts + loadThread → 重新渲染联系人 + 消息面板
```

**模式 5: SSE → 后台刷新**
```
EventSource message → refreshInBackground(true) → refreshAll(true) → (静默) loadContacts + loadThread
```
静默模式：如果用户正在滚动则推迟；如果数据未变则跳过重新渲染。新消息检测使用 `state.lastKey` 比较。

### 5.3 缓存与失效

- **联系人列表：** 仅当 `contactRenderKey()` 与 `state.contactsRenderKey` 不同时才渲染。单字段变更触发完整重新渲染。
- **消息面板页面：** 缓存在 `state.threadPages{}` map 中。LRU 淘汰保留最多 `THREAD_PAGE_CACHE_LIMIT`（20）个条目。当前选中联系人的页面永不被淘汰。
- **消息面板消息：** 每个页面最多 `THREAD_PAGE_MAX_MESSAGES`（200）条。更早的消息从前端裁剪。
- **更早消息（分页）：** 滚动到顶部或上滑或下滑时加载。通过 `mergeThreadMessages()` 合并到现有页面，按 `type:sortId` 键去重，保留最后出现的条目。
- **渲染 key：** 联系人和消息面板均使用渲染 key 在静默刷新时避免不必要的 DOM 变更。
- **加载序列号：** `state.threadLoadSeqByContact[id]` 在每次 `loadThread()` 调用前递增。响应处理器检查序列号是否匹配当前值；不匹配则丢弃响应（过期闭包保护）。
- **通话详情代数：** `state.callDetailGeneration` 在每次 `clearCallDetailActivity()` 时递增，对通话记录加载和轮询起到类似的过期保护作用。

### 5.4 未读消息追踪

- 每次 `loadContacts()` 调用时，`reconcileUnreadContacts()` 比较当前 `messageCount` 和 `lastTime` 与之前的快照（`state.contactSnapshots`）。
- 如果联系人未被选中且有新收件消息（`lastDirection !== 'outbound'`），递增 `state.unreadByContact[id]`。
- 当联系人被选中时，`clearContactUnread(id)` 删除对应条目。
- 增量计算为 `Math.max(0, current.messageCount - previous.messageCount)`，如果时间推进则最小增量为 1。
- 上限 99。

## 6. 交互流程

### 6.1 登录流程

**生产模式：**
1. 页面加载。调用 `initWeComLogin()`。
2. `POST /api/v1/wecom/login/attempts` 返回 `{loginType, appId, redirectUri, state}`。
3. 企业微信 SDK（`ww.createWWLoginPanel`）将二维码渲染到 `#wwLoginPanel`。
4. 状态更新："正在准备二维码" → "请使用企业微信扫码登录" → "请在企业微信中确认登录"。
5. 用户在企业微信 App 中扫描二维码。
6. `onLoginSuccess({code})` 触发 → `completeWeComLogin(code)`。
7. `POST /api/v1/wecom/login/exchange` 携带 `{code, state}` 返回 `{viewerAuthToken, expiresIn}`。
8. 认证信息存入 `state.wecomAuth`，过期时间计算为 `Date.now() + expiresIn * 1000`。
9. 登录屏幕隐藏，主界面显示。
10. 调用 `enterMessageCenter()`：
    - 获取渠道能力、模板、联系人。
    - 绑定事件处理器（按钮、滚动、键盘、可见性）。
    - 连接 EventSource。
    - 启动 5 秒刷新间隔。

**本地开发模式 (`LOCAL_DEV_MODE = true`)：**
1. 登录屏幕立即隐藏，主界面显示。
2. 仍通过 `POST /api/v1/wecom/login/attempts` 创建登录尝试。
3. 直接调用 `completeWeComLogin('local-development-code')`。
4. `enterMessageCenter()` 正常进行。

**认证过期：**
- `currentWeComAuth()` 检查 `Date.now() >= state.wecomAuthExpiresAt`，过期则抛出 `WECOM_VIEWER_AUTH_EXPIRED`。
- `handleViewerAuthFailure()` 捕获 401/403 响应并调用 `returnToWeComLogin()`。
- `returnToWeComLogin()` 重置认证状态、关闭 EventSource、停止刷新定时器、清除通话详情活动、显示登录屏幕、重启登录流程。
- 多个并发 401 通过 `state.viewerReloginPromise` 去重。

### 6.2 查看联系人列表

1. `loadContacts(false)` 获取 `GET /api/contacts`。
2. 填充 `state.contacts`。
3. `renderContacts()`：
   - 应用来自 `#searchInput` 的搜索过滤（大小写不敏感的 JSON 子串匹配）。
   - 将每个联系人渲染为 `.contact` div。
   - 选中的联系人添加 `.active` 类。
   - 未读联系人显示徽章/红点。
4. 在 `#searchInput` 中键入时，每次 `input` 事件触发 `renderContacts()`（由 DOM input 事件时机自然去抖，无人工去抖）。
5. 更新顶栏中的联系人数量。

### 6.3 查看会话消息面板

1. 用户点击列表中的联系人。`selectContact(id)` 触发。
2. 如果切换联系人：清除消息/通话选择、关闭资料模态框、清除通话详情活动。
3. `state.selectedPointId = id`、`state.selectedMessageId = ''`、`clearContactUnread(id)`。
4. `state.selectedChannel` 设为 `contact.channels` 中的第一个匹配渠道。
5. `renderContacts()` 重新渲染以高亮选中联系人。
6. `loadThread(id, false)`：
   - 递增 `threadLoadSeqByContact[id]`。
   - 获取 `GET /api/v1/contacts/{id}/timeline?limit=10`。
   - 响应时：检查加载序列号、合并消息、更新 `state.threadPages[id]`、限制页面大小、更新 LRU 缓存。
   - `renderThreadMessages()` 构建 DOM：消息行（带方向类）、媒体预览、元信息行。
   - 滚动到底部 (`threadEl.scrollTop = threadEl.scrollHeight`)。
7. `renderComposer()` 渲染带适当渠道标签的发送器。
8. `renderContactDetail(contact)` 在详情面板中渲染联系人信息。

**无限滚动（加载更早消息）：**
1. 当 `#thread` 滚动到接近顶部 (<24px) 或用户上滑/下滑时，`loadOlderThreadMessages()` 触发。
2. 检查 `nextCursor` 和 `isLoadingOlder` 防止重复加载。
3. 通过 `viewerApi(threadPageUrl(id, page.nextCursor))` 获取下一页。
4. 响应时：检查消息面板 revision。如果 revision 变了，重新加载整个消息面板（丢弃缓存）。否则将更早条目合并到前端。
5. 通过补偿新增高度保持滚动位置。

**滚动的去抖：**
- 滚动面添加 `.scrolling` 类，`state.isUserScrolling = true`。
- 800ms 无活动后清除滚动状态。
- 滚动期间，静默刷新被推迟 (`state.pendingSilentRefresh = true`)。

### 6.4 发送邮件

1. 选择有邮件渠道的联系人。发送器显示邮件标签。
2. 从下拉列表选择收件人（从 `contact.points` 过滤 `channel === 'email'` 填充）。
3. 输入主题和正文。
4. 点击"发送邮件"或按 Enter。
5. `sendEmail()` 验证 `to` 和 `subject` 非空。
6. `POST /api/send/email` 携带 `{to, subject, body}`。
7. 成功后：清空正文、显示提示"邮件已发送"、调用 `refreshAll(false)`。

### 6.5 发送 ChatApp 消息（文本模式）

1. 选择有 ChatApp 渠道的联系人。发送器显示 ChatApp 标签。
2. 默认模式为文本。可通过图标按钮切换到模板/图片/视频/文档。
3. 输入消息文本。可通过表情面板插入表情。
4. 按 Enter（不带 Ctrl）或点击"发送 WhatsApp"。
5. `sendChatText()`：`POST /api/send/chatapp` 携带 `{mode:'text', to, text, clientRequestId}`。
6. 成功后：清空文本、提示、刷新全部。

### 6.6 发送 ChatApp 模板

1. 切换到模板模式。模板下拉从 `state.templates` 填充。
2. 根据选中模板的 `placeholders` 数组渲染动态占位字段。
3. 填写占位值。
4. 点击"发送模板"。
5. `sendTemplate()`：`POST /api/send/chatapp` 携带 `{mode:'template', to, templateCode, templateName, languageCode, templateParamsJson, clientRequestId}`。

### 6.7 发送 ChatApp 媒体

1. 切换到图片/视频/文档模式。
2. 点击拖放区选择文件（一个 `<label>` 对应隐藏的 `<input type="file">`）。
3. 可选添加标题。
4. 点击"发送附件"。
5. `sendMedia()`：`POST /api/send/chatapp-media` 以 `FormData` 格式携带 `{to, mediaType, caption, clientRequestId, file}`。

### 6.8 上传通话录音

1. 将发送器切换到"电话记录"标签。
2. 选择 MP3 文件（校验：`.mp3` 扩展名，最大 `CALL_MAX_AUDIO_BYTES` = 100 MiB）。
3. 选择方向（呼入/呼出）、通话时间（datetime-local 选择器）、电话号码（如有多个号码）。
4. 点击"上传录音"。
5. `uploadCallRecord()` 使用 `XMLHttpRequest`（非 `fetch`）支持上传进度：
   - `POST /api/v1/contacts/{contactId}/call-records` 以 `FormData` 格式。
   - `xhr.upload.onprogress` 更新 `<progress>` 条和状态文本。
   - 成功后：进度设为 100、显示状态、刷新消息面板。
   - 错误时：在状态文本和提示中显示错误。

### 6.9 合并联系人（拖放）

1. 将一个联系人行拖到另一个。
2. `ondrop` 处理器调用 `POST /api/contact-groups/merge` 携带 `{primaryPointId: target, mergedPointId: source}`。
3. `state.selectedPointId` 设为目标。
4. `refreshSelectedContactViews(false)` 重新获取联系人、消息面板和发送器。
5. 提示"联系人已合并"。

### 6.10 拆分联系人账号

1. 在详情面板中，点击账号旁边的拆分图标按钮。
2. `splitAccount(pointId)` 调用 `POST /api/contact-groups/split` 携带 `{primaryPointId: state.selectedPointId, pointToSplit: pointId}`。
3. 清除资料修改状态、选中消息。
4. 刷新选中联系人视图。
5. 提示"账号已拆分"。

### 6.11 编辑联系人资料

1. 点击消息面板标题旁的编辑铅笔图标（选中联系人时可见）。
2. `openProfileModal()` 用当前 `remark` 和 `tags`（用中文逗号"，"连接）填充输入框，显示模态框，聚焦昵称输入框。
3. 编辑昵称和/或标签。`oninput` 触发 `markProfileDirty()` 将 `state.profileDirty = true` 并显示"未保存"。
4. 点击"保存"或按 Enter。
5. `saveContactProfile()`：`POST /api/contact-groups/profile` 携带 `{contactPointId, nickname, tags}`。
6. 成功后：`refreshAll(false)`、重新渲染发送器、关闭模态框、提示"联系人资料已保存"。
7. 取消/关闭：重置修改标记，隐藏模态框。

### 6.12 查看消息详情

1. 点击消息面板中的消息气泡。
2. `selectMessage(id)` 设置 `state.selectedMessageId = id`，切换所有消息的 `.active` 类，清除通话详情。
3. 获取 `GET /api/messages?id={id}`。
4. `renderMessageDetail(m)` 在详情面板中渲染 KV 对和原始 JSON。

### 6.13 查看通话记录详情

1. 点击消息面板中的通话记录卡片。
2. `openCallRecordDetail(id)` 清除之前的通话详情，设置 `state.selectedCallRecordId = id`，递增 `state.callDetailGeneration`。
3. 显示"正在加载电话记录"占位。
4. 获取 `GET /api/v1/call-records/{id}`。
5. `renderCallRecordDetail(detail)` 渲染完整详情面板：音频播放器、转录、分段、修订。
6. 如果转录状态为 `queued` 或 `processing`，开始轮询 (`scheduleCallDetailPoll`)。
7. 调用 `renewCallAudioSession()` 获取音频会话。成功后启动定期续期 (`startCallAudioRenewal()`)。

**轮询详情：**
- 定期轮询 `GET /api/v1/call-records/{id}`。
- 延迟从 `CALL_DETAIL_POLL_MS`（3 秒）开始，每次失败翻倍，上限 `CALL_DETAIL_POLL_MAX_MS`（30 秒）。
- 连续失败 `CALL_DETAIL_POLL_MAX_FAILURES`（5）次后停止。
- 每次轮询：更新详情面板（保留 audio 元素以避免中断播放），如果状态变为 completed/failed 则刷新消息面板。
- 轮询中的 401/403 触发重新登录流程。

**音频会话续期：**
- 必须先调用 `POST /api/v1/call-records/{id}/audio-sessions` 获取播放令牌。
- 会话每 `CALL_AUDIO_RENEW_MS`（240 秒）续期一次。
- 如果 `<audio>` 元素出错，尝试通过续期并重新加载音频来恢复一次（保持 `currentTime`）。
- 页面可见性变为隐藏时停止续期；再次可见时续期。

### 6.14 修订通话转录

1. 在通话记录详情中，编辑"修订转录"文本区中的文本。
2. 点击"保存修订"。
3. `reviseCallRecord()`：`PATCH /api/v1/call-records/{id}/transcript` 携带 `{text, expectedVersion: detail.version}`。
4. 成功后：重新渲染通话详情、刷新消息面板、提示"转录修订已保存"。

### 6.15 重试通话转录

1. 如果通话记录的转录失败且 `error.retryable = true`，显示"重新转录"按钮。
2. `retryCallRecord()`：`POST /api/v1/call-records/{id}/retry` 携带 `{clientRequestId}`。
3. 成功后：重新渲染详情、开始轮询、刷新消息面板、提示"已重新加入转录队列"。

### 6.16 查看企业微信会话

1. 在发送器中选择企业微信标签（有企业微信渠道的联系人可用）。
2. 点击"打开企业微信会话"。
3. `loadWeComViewer()`：
   - 创建会话：`POST /api/v1/wecom/conversation-view/sessions`。
   - 获取会话详情：`GET /api/v1/wecom/conversation-view/sessions/{id}`。
   - 生产模式：获取 JS-SDK 配置、初始化企业微信 SDK、使用消息列表创建 OpenData frame。
   - 本地开发模式：渲染示例消息。
4. 企业微信 OpenData frame 使用 `<ww-open-message>` 组件渲染每条消息。
5. 组件错误：如认证过期则触发重新登录流程，否则显示错误并通过 `POST /api/v1/wecom/conversation-view/events` 审计。

### 6.17 后台刷新

- 每 5 秒触发 `refreshInBackground(true)`。
- 调用 `loadContacts(true)`（静默），如果选中了联系人则调用 `loadThread(id, true)`（保持滚动）。
- 如果用户正在滚动 (`state.isUserScrolling`)，刷新推迟到滚动停止后。
- 刷新期间的认证失败触发重新登录。
- SSE 事件也触发相同的后台刷新路径。

## 关键架构观察

1. **无框架：** 整个 SPA 是命令式原生 JavaScript，通过 `innerHTML` 和 `document.querySelectorAll` 手动操作 DOM。
2. **无虚拟 DOM diffing：** 渲染 key（`contactsRenderKey`、`threadRenderKeyByContact`）是手动计算的字符串，用于在数据未变更时跳过重新渲染。
3. **无客户端路由：** 所有导航通过状态标志实现（`selectedPointId`、`selectedMessageId`、`selectedCallRecordId`、`selectedChannel`、`selectedMode`）。
4. **两种 API 客户端变体：** `api()` 用于非认证接口，`viewerApi()` 注入企业微信查看者认证令牌。区分方式为每个接口硬编码。
5. **XHR 用于文件上传：** 通话录音上传使用原生 `XMLHttpRequest` 而非 `fetch`，以支持上传进度事件。其他上传均使用 `fetch`。
6. **竞态条件处理：**
   - `threadLoadSeqByContact` 用于消息面板加载。
   - `callDetailGeneration` 用于通话详情加载和轮询。
7. **滚动位置保持：** 消息面板滚动通过计算 `oldBottom`（距滚动底部的距离）并在重新渲染后恢复来在刷新间保持。
8. **认证流程与 UI 交织：** 登录屏幕和主界面是互斥的 DOM 状态，通过 `hidden` 属性切换。认证令牌存储在 `state.wecomAuth` 中，通过 `currentWeComAuth()` 检查，过期时同步抛出。
9. **联系人未读徽章系统** 完全在客户端实现：跟踪连续 `contactSnapshots` 之间的增量，仅在联系人有新收件消息且未被选中时递增。
