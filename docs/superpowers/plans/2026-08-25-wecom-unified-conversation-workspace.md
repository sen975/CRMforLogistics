# 企业微信统一会话工作区实施计划

> **For agentic workers:** 本计划按 TDD 执行。每个任务先写失败测试，再实现最小闭环，专项测试通过后独立提交；最后一个任务才运行全量回归、真实浏览器验收和打包。

## Goal

把联系人直聊和企业微信群纳入同一个可访问、可审计的会话工作区：左侧统一列出 `CONTACT` 与 `WECOM_GROUP`，联系人下的多个 `DIRECT` 源会话聚合为一个企业微信入口，群聊按 `sourceConversationId/chatId` 独立展示；中间区域在企业微信模式下铺满原时间轴区域，并始终复用一个 OpenDataFrame。ChatApp、邮件和电话继续使用联系人混合时间轴。

## Architecture

后端由统一会话查询服务拥有可见性、排序、分页、直聊聚合和群独立语义；`wecom_parties`、源会话和参与关系是 sender、participants、头像、昵称及跳转资格的唯一事实来源。前端只消费判别联合响应和 canonical 路由，不根据 userid、显示名或方向猜测业务语义。`ConversationWorkspace` 持有一个稳定的 frame host，`WeComFrameController` 是 `setData()` 的单一写入者，以 generation + AbortSignal + 有界内存缓存保证清空/准备/填充严格串行。

## Non-goals and stop conditions

- 不创建第二个 OpenDataFrame，不通过卸载/重建 frame 规避竞态。
- 不把群写入 `contacts`，不创建虚假 CRM 联系人，不恢复联系人内多个企业微信标签。
- 不改变代开发安装实例、ChatData 游标、成员绑定和既有权限模型。
- 不在日志、URL、localStorage、IndexedDB 或构建产物中写入 token、secretKey、消息正文或完整目标 ID。
- 出现第二个 frame、并发 `setData()`、群被归入联系人、发送者/方向错误、越权跳转、永久占位符或 `resolve session fail` 时停止发布。

## 2026-08-27 需求变更补充：头像、群名与 frame 元数据

本补充是当前计划的强制验收项，优先级高于旧的“有消息即可展示”表述。它不改变单 frame 清空重填策略，也不新增 frame。

### API 合同核对

- 员工资料：代开发安装实例 token -> `GET /cgi-bin/user/get`（官方文档 96255），读取 `name` 和在授权条件满足时的 `avatar`；头像为空必须保留 `PARTIAL`，不能伪造。
- 外部联系人资料：代开发安装实例 token -> `GET /cgi-bin/externalcontact/get`（官方文档 96315），读取 `external_contact.remark/name/nickname/alias`；官方代开发边界不允许把客户头像当成必得字段。
- 客户群资料：代开发安装实例 token -> `POST /cgi-bin/externalcontact/groupchat/get`（官方文档 96338），读取 `group_chat.name` 与实际返回的 `member_list`；不适用于企业内部群。
- 企业内部群资料：数据与智能专区 SDK `get_group_chat`（官方文档 100025）；不得用客户群 API 或 `receiver_list` 推断完整成员。
- 正文 frame：`ww.register`、`ww.initOpenData`、`ww.createOpenDataFrameFactory`、`ww-open-message`（官方文档 100049、94325）只负责受保护正文。昵称、头像、时间是应用外层模板字段，不是 `ww-open-message` 的隐式返回值。

### 前端 frame 合同

`WeComConversationFrame` 生成的每个 `msgList` 元素必须是：

```text
{ msgid, secretKey, direction, occurredAt, senderDisplayName?, senderAvatarUrl? }
```

- 单聊：显示每条消息的 `occurredAt`，不显示发送者昵称；头像显示真实 URL，缺失时显示默认头像。
- 群聊：显示每条消息的 `occurredAt`；将该条 `senderDisplayName` 渲染在气泡上方，将该条 `senderAvatarUrl` 与气泡顶部同层对齐；缺失资料分别显示“未获取昵称”和默认头像。
- 发送者资料必须按 `msgid -> MessageResponse.sender` 一一绑定，禁止用上一条消息、userid 或方向推断，A/B 交替 fixture 必须不串位。
- `WeComGroupThread.displayName` 为空时显示“企业微信群”，禁止显示长 chat ID。

### 变更后的专项验收步骤

- 后端：用群线程接口检查 `items[].occurredAt`、`items[].sender.displayName`、`items[].sender.avatarUrl` 和 `displayName` 空值降级。
- 前端：检查 `setData` 调用数据和真实 frame，确认昵称位于群气泡上方、头像与气泡顶部同层、时间对每条消息可见。
- 实机：使用至少四条 A、B、A、B 交替消息，以及缺昵称/缺头像/缺群名数据，保存浏览器截图和 Network 脱敏响应。
- 失败门禁：任一消息无时间、群消息昵称串位、完整 userid/chat ID 出现在 UI、或把空头像误判为接口过期，均不得发布。
- 在线复核：服务器恢复 DNS/网络后，重新核对官方文档 96255、96315、96338、100025、100049、94325；在此之前不得新增未列入白名单的企业微信接口。

## Shared contracts

### `ConversationListItem`

```text
ConversationListItem = ContactConversationItem | WeComGroupConversationItem

ContactConversationItem {
  type: CONTACT; id: UUID; displayName; avatarUrl?; channelTypes[];
  lastMessageAt?; lastText?; messageCount; unreadCount
}

WeComGroupConversationItem {
  type: WECOM_GROUP; id: sourceConversationId UUID;
  providerConversationKey: chatId; displayName; avatarUrl?;
  participantCount; lastMessageAt?; lastText?; messageCount; unreadCount
}
```

列表由后端按当前 CRM 用户和绑定的企业微信成员过滤、搜索、排序和分页；前端禁止分别请求两个列表后自行合并。

### `WeComPartyView` and thread responses

```text
WeComPartyView {
  partyId: UUID; partyType: EMPLOYEE | EXTERNAL_CONTACT | ROBOT;
  providerPartyId: string; displayName; avatarUrl?;
  contactId?: UUID; contactAccessible: boolean; isCurrentViewer: boolean
}

WeComGroupThread {
  sourceConversationId; providerConversationKey; displayName; avatarUrl?;
  openClientUrl?; participants: WeComPartyView[]; items[];
  nextCursor?; messageCount; threadRevision
}
```

`contactId` 仅由后端在真实 CRM 联系人且当前用户可访问时返回。群跳转按钮仅在后端返回经白名单校验的官方 `openClientUrl` 时出现。

客户群详情页可使用已核对的 `externalcontact/groupchat/get` 补全成员；该接口不得从通用 ChatData scheduler 对所有 `chatid` 无条件调用。企业内部群没有同等官方全量成员接口，只保留 ChatData 已观察到的 sender/receiver_list。ChatData `sender.type=3` 使用独立 `ROBOT` party，不进入联系人投影。

### Viewer target and events

```text
WeComViewerSessionRequest {
  targetType: CONTACT | WECOM_GROUP;
  targetId: UUID;
  messageIds: string[];
}
```

Controller 将当前 CRM `userId` 传入 Service。Service 对联系人验证外部联系人归属，对群验证源会话参与关系；事件上报按 `generation + viewerSessionId + errorCategory` 幂等，次生 403 不能覆盖 SDK 原始错误。

## Task 1: 后端统一会话列表

**Files:**

- Create `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ConversationListItemResponse.java`。
- Create `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/conversation/UnifiedConversationService.java`。
- Modify `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ConversationMapper.java`。
- Modify `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactController.java`。
- Create `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/conversation/UnifiedConversationServiceTest.java`。
- Modify `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ConversationMapperAssignmentIntegrationTest.java` and `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ConversationMapperSourceConversationSqlTest.java`。

**Interfaces:**

- `UnifiedConversationService.list(userId, search, cursor, limit): Page<ConversationListItemResponse>`。
- SQL 必须在服务端完成联系人和群的权限过滤、统一排序、稳定 cursor 分页和去重；群未读/最后消息只统计该 `sourceConversationId`。

- [ ] 写失败测试：CONTACT 与 WECOM_GROUP 混合排序、两个群独立、非参与群不可见、分页无重无漏、同联系人多个 DIRECT 只有一个入口。
- [ ] 运行 `cd demo/message-center-spring/backend && mvn -Dtest=UnifiedConversationServiceTest test`，确认新增断言先失败。
- [ ] 实现 DTO、mapper SQL 和 service/controller 接线；不在 React 中拼接分页结果。
- [ ] 运行专项测试和 `mvn -DskipTests compile`，确认通过。
- [ ] 独立提交：`feat: add unified conversation list contract`。

## Task 2: 后端 WeCom 线程、sender 和 participants 合同

**Files:**

- Create `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/WeComPartyView.java`。
- Create `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/WeComThreadResponse.java`。
- Create `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/WeComGroupThreadResponse.java`。
- Modify `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/ThreadService.java`。
- Modify `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactService.java`。
- Modify `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageProjector.java`。
- Modify `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/MessageMapper.java` and `ConversationMapper.java`。
- Modify `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ThreadController.java`。
- Create `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageProjectorGroupTest.java` and modify `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/MessageQueryServiceTest.java`。

**Interfaces:**

- `ThreadService.getContactWeComThread(userId, contactId, cursor, limit)` aggregates all accessible DIRECT source conversations while retaining each message's source ID.
- `ThreadService.getWeComGroupThread(userId, sourceConversationId, cursor, limit)` returns only that group and validates participation.
- Message projections expose structured `sender`, not a frontend-derived direction/name.

- [ ] 写失败测试：员工与外部联系人 sender、群参与者姓名/头像/partyType、无 CRM 联系人不生成 contactId、非参与群返回 403、DIRECT 聚合保留源审计字段。
- [ ] 运行 `cd demo/message-center-spring/backend && mvn -Dtest=WeComMessageProjectorGroupTest,MessageQueryServiceTest test`，确认失败。
- [ ] 实现 mapper/service/controller 合同；复用 V23 表，不新增迁移，除非现有 schema 证明缺列。
- [ ] 运行 `cd demo/message-center-spring/backend && mvn -Dtest=WeComMessageProjectorGroupTest,MessageQueryServiceTest,WeComConversationSchemaContractTest test && mvn -DskipTests compile`。
- [ ] 独立提交：`feat: expose structured wecom thread identities`。

## Task 3: Viewer 目标校验和事件幂等

**Files:**

- Modify `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/WeComViewerSessionRequest.java`。
- Modify `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/WeComViewerController.java`。
- Modify `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComViewerService.java`。
- Modify `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/WeComViewerEventRequest.java`、`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComViewerAuditMapper.java` and `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComViewerAuditEntity.java`。
- Modify `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComViewerServiceTest.java` and `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/WeComViewerControllerTest.java`。

**Interfaces:**

- `createViewerSession(crmUserId, ViewerTarget target, List<String> messageIds)` validates CONTACT/GROUP target ownership and returns only authorized references.
- `recordClientEvent(eventKey, stage, generation, viewerSessionId, errorCategory)` is idempotent and redacts target/token/secret data.

- [ ] 写失败测试：群目标可读、联系人目标可读、越权目标 403、重复事件只落一条、空 viewerSessionId 不伪造审计。
- [ ] 运行 `cd demo/message-center-spring/backend && mvn -Dtest=WeComViewerServiceTest,WeComViewerControllerTest test`，确认失败。
- [ ] 实现目标判别、当前 CRM actor 传递和幂等事件约束；不得改变安装实例 token 生成链路。
- [ ] 运行 `cd demo/message-center-spring/backend && mvn -Dtest=WeComViewerServiceTest,WeComViewerControllerTest test && mvn -DskipTests compile`。
- [ ] 独立提交：`fix: validate wecom viewer targets and dedupe events`。

## Task 4: 前端 API 类型、统一左侧列表和 canonical 路由

**Files:**

- Modify `demo/message-center-spring/frontend/src/api/types.ts` and `demo/message-center-spring/frontend/src/api/endpoints.ts`。
- Modify `demo/message-center-spring/frontend/src/hooks/useContacts.ts`。
- Modify `demo/message-center-spring/frontend/src/pages/ContactsPage.tsx`。
- Modify `demo/message-center-spring/frontend/src/router.tsx` and `demo/message-center-spring/frontend/src/components/AppLayout.tsx`。
- Create/modify `demo/message-center-spring/frontend/src/pages/ConversationWorkspace.tsx` and `demo/message-center-spring/frontend/src/router.test.tsx`。
- Add `demo/message-center-spring/frontend/src/pages/ContactsPage.unified.test.tsx`。

**Interfaces:**

- `listConversations(params): Page<ConversationListItem>` consumes the backend discriminated union.
- Canonical paths are `/conversations/contact/:contactId` and `/conversations/wecom-group/:sourceConversationId`; old `/thread/:contactId` may only redirect during deployment compatibility and cannot own state.

- [ ] 写失败测试：联系人和群混合列表、群进入独立路由、群不可拖拽合并、旧路径只重定向、列表分页不重复。
- [ ] 运行 `cd demo/message-center-spring/frontend && npx vitest run src/pages/ContactsPage.unified.test.tsx src/router.test.tsx`，确认失败。
- [ ] 实现 API 类型、统一列表和路由；不在页面根据 userid 拼名称或跳转 URL。
- [ ] 运行专项测试和 `npx tsc -b --pretty false`。
- [ ] 独立提交：`feat: add unified conversation workspace routes`。

## Task 5: 联系人/群工作区和单 OpenDataFrame controller

**Files:**

- Create/modify `demo/message-center-spring/frontend/src/components/ConversationWorkspace.tsx`。
- Modify `demo/message-center-spring/frontend/src/pages/ThreadPage.tsx`。
- Modify `demo/message-center-spring/frontend/src/components/wecom/WeComConversationPanel.tsx` and `demo/message-center-spring/frontend/src/components/wecom/WeComConversationFrame.tsx`。
- Modify `demo/message-center-spring/frontend/src/hooks/useWeComViewer.ts` and `demo/message-center-spring/frontend/src/wecom/wecomSdk.ts`。
- Create/modify `demo/message-center-spring/frontend/src/wecom/WeComFrameController.ts` and `demo/message-center-spring/frontend/src/wecom/WeComFrameController.test.ts`。
- Modify `demo/message-center-spring/frontend/src/wecom/WeComViewerMessageCache.ts` only through its source API; do not hand-edit dist。

**Interfaces:**

- `WeComFrameController.update(target, items): Promise<FrameStatus>` owns generation, abort, timeout and the only `setData` calls.
- `ConversationWorkspace` remains mounted across target changes and supplies either mixed timeline props or CONTACT/GROUP full-height WeCom props.
- `prepareMessages(targetType, targetId, messageIds, signal)` returns ordered authorized `{msgid, secretKey}` references, using bounded in-memory cache for hits.

- [ ] 写失败测试：A→B、A→B→A、A→群→B 只创建一个 frame；任意时刻只有一个 pending `setData`；顺序为 clear→fill；旧 generation 不能回写；超时/SDK 失败显示明确错误和重试。
- [ ] 运行 `cd demo/message-center-spring/frontend && npx vitest run src/wecom/WeComFrameController.test.ts src/components/wecom/WeComConversationFrame.test.tsx src/components/wecom/WeComConversationPanel.test.tsx src/hooks/useWeComViewer.test.ts`，确认失败。
- [ ] 实现稳定 host、严格串行状态机、AbortSignal、generation 检查和超时；不得给 frame 子组件增加会导致卸载的 React `key`，不得创建第二 frame。
- [ ] 保留单 frame 清空重填策略；联系人切换不改变 ChatApp/邮件/电话混合时间轴及离开前滚动位置。
- [ ] 运行 `cd demo/message-center-spring/frontend && npx vitest run src/wecom/WeComFrameController.test.ts src/components/wecom/WeComConversationFrame.test.tsx src/components/wecom/WeComConversationPanel.test.tsx src/hooks/useWeComViewer.test.ts && npx tsc -b --pretty false && npm run build`。
- [ ] 独立提交：`fix: serialize unified wecom frame updates`。

## Task 6: 群参与者、消息发送者和跳转 UI

**Files:**

- Modify `demo/message-center-spring/frontend/src/components/wecom/WeComConversationPanel.tsx` and `WeComConversationFrame.tsx`。
- Create/modify `demo/message-center-spring/frontend/src/components/wecom/WeComGroupHeader.tsx` and `demo/message-center-spring/frontend/src/components/wecom/WeComGroupParticipants.tsx`。
- Modify `demo/message-center-spring/frontend/src/components/MessageBubble.tsx` only for structured WeCom sender rendering。
- Add `demo/message-center-spring/frontend/src/components/wecom/WeComGroupParticipants.test.tsx`, `demo/message-center-spring/frontend/src/components/wecom/WeComConversationPanel.group.test.tsx`, and `demo/message-center-spring/frontend/src/components/wecom/WeComSenderLink.test.tsx`。

**Interfaces:**

- 群头部显示群名和参与人数；参与者区显示姓名、头像、类型。
- `contactAccessible=true` 才导航到 `/conversations/contact/:contactId`；员工无 CRM 联系人时只显示姓名/头像，不显示链接。
- 直聊保留官方“在企业微信中打开”；群聊只消费后端 `openClientUrl`，不存在则不渲染按钮；企业微信模式不渲染发送输入框。

- [ ] 写失败测试：群内四位成员分别显示真实 sender、同名群不合并、可访问联系人可跳转、不可访问员工不可跳转、无 openClientUrl 无按钮、面板铺满父容器。
- [ ] 运行 `cd demo/message-center-spring/frontend && npx vitest run src/components/wecom/WeComGroupParticipants.test.tsx src/components/wecom/WeComConversationPanel.group.test.tsx src/components/wecom/WeComSenderLink.test.tsx`，确认失败。
- [ ] 实现布局和结构化 sender/participant 投影消费；不在 UI 推断方向或伪造联系人。
- [ ] 使用真实 Ant Design token/现有 icon 组件，检查桌面和窄屏无重叠。
- [ ] 运行 `cd demo/message-center-spring/frontend && npx vitest run src/components/wecom/WeComGroupParticipants.test.tsx src/components/wecom/WeComConversationPanel.group.test.tsx src/components/wecom/WeComSenderLink.test.tsx && npx tsc -b --pretty false`。
- [ ] 独立提交：`feat: render wecom group participants and senders`。

## Task 7: 混合时间线、历史分页和错误观测回归

**Files:**

- Modify `demo/message-center-spring/frontend/src/pages/ThreadPage.tsx`。
- Modify `demo/message-center-spring/frontend/src/components/wecom/WeComTimelineSegment.tsx` and `demo/message-center-spring/frontend/src/wecom/segmentWeComTimeline.ts`。
- Modify `demo/message-center-spring/frontend/src/hooks/useMessages.ts` to enforce the history request generation guard。
- Modify `demo/message-center-spring/frontend/src/wecom/wecomErrors.ts` and `demo/message-center-spring/frontend/src/hooks/useWeComViewer.ts` for event reporting.
- Modify `demo/message-center-spring/frontend/src/pages/ThreadPage.wecom.test.tsx`, `demo/message-center-spring/frontend/src/components/wecom/WeComTimelineSegment.test.tsx`, and `demo/message-center-spring/frontend/src/wecom/wecomErrors.test.ts`。

**Interfaces:**

- 历史页 prepend 后向企业微信面板传入当前联系人全部去重消息；旧分页响应不能写入新联系人；滚动位置保持。
- SDK HTTP 200 + result failure 必须记录脱敏 `stage/generation/errcode`；同一错误最多上报一次，`/events` 403 不覆盖原始错误。

- [ ] 写失败测试：历史 m1..m4 合并、A 的迟到分页不污染 B、A→B→A 旧错误不显示、事件去重和登录过期态。
- [ ] 运行 `cd demo/message-center-spring/frontend && npx vitest run src/pages/ThreadPage.wecom.test.tsx src/components/wecom/WeComTimelineSegment.test.tsx src/wecom/wecomErrors.test.ts`，确认失败。
- [ ] 实现 generation guard、错误分类、去重和明确空态/重试文案；不记录消息正文、token、secretKey。
- [ ] 运行 `cd demo/message-center-spring/frontend && npx vitest run src/pages/ThreadPage.wecom.test.tsx src/components/wecom/WeComTimelineSegment.test.tsx src/wecom/wecomErrors.test.ts && npx tsc -b --pretty false`。
- [ ] 独立提交：`test: cover wecom history and diagnostic regressions`。

## Task 8: 全量回归、真实 Chrome 验收、服务器脚本和打包

**Files:**

- Create/modify `demo/message-center-spring/scripts/wecom-server-acceptance.sh`。
- Modify `docs/superpowers/README.md` to index this plan after implementation starts/completes。
- Create `docs/superpowers/reviews/2026-08-25-wecom-unified-conversation-workspace-readiness.md` only after evidence is collected。
- Generated `backend/target/*.jar`, `frontend/dist`, and zip files are release artifacts only; never hand-edit or stage unrelated existing packages。

**Commands and gates:**

- [ ] 后端全量：`cd demo/message-center-spring/backend && mvn test`。
- [ ] 前端全量：`cd demo/message-center-spring/frontend && npm test && npm run build`。
- [ ] 静态 API 门禁：扫描生产源码中的 `/cgi-bin/` 路径，确认只使用已核准的代开发/ChatData API，`user/list_id` 不存在。
- [ ] 真实 Chrome 桌面和移动宽度检查：CONTACT、WECOM_GROUP、A→B、A→B→A、A→群→B、刷新/重试、空态、滚动恢复；确认单 frame、无并发 `setData`、无重叠和消息正文可见。
- [ ] 服务器脚本只读检查：运行 Jar SHA-256、前端入口资产 hash、API HTTP/业务 result、`/events` 去重、群参与者和联系人跳转；输出脱敏 trace/error，不输出密钥。
- [ ] 只有全部门禁通过后才生成 Jar/dist zip，记录 SHA-256、迁移版本、备份目录和回滚命令；任一停止条件触发则不打包。
- [ ] 独立提交：`test: verify unified wecom workspace release gates`。

## Final self-review

- [ ] 逐条对照设计真源的目标、边界、唯一 owner、后端合同、frame 生命周期、错误合同和验收条目，确认没有遗漏或旧语义残留。
- [ ] 搜索计划中的未闭合占位语句并全部清除。
- [ ] 复核所有文件路径、Java/TypeScript 类型名、HTTP 路由和测试命令与当前仓库一致。
- [ ] 确认计划不要求回滚或覆盖用户未跟踪的三个前端 zip，也不把生成物当源码修改。
