# 企业微信群会话、方向与切换稳定性实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让企业微信群聊独立展示、内部员工方向按当前成员正确计算，并消除联系人 A→B 的 frame 空白竞态。

**Architecture:** 后端以 `sourceConversationId` 作为 WeCom 源会话唯一投影键，Thread API 返回源会话元数据；方向由后端按当前绑定成员和参与者关系计算，前端不猜业务语义。前端按源会话分组，viewer 保持一个 frame 清空后重填，并用 generation + 最新请求优先更新避免旧 Promise 阻塞。

**Tech Stack:** Java 17、Spring Boot、MyBatis-Plus、PostgreSQL/Flyway、React 18、TypeScript、Vitest、Vite。

## Global Constraints

- 不回滚或覆盖工作树中已有用户改动。
- 群聊不得创建或伪造 `contact_identity_id`。
- 不扩大企业微信 viewer 的成员参与者权限过滤。
- 不把消息正文、token、secretKey 写入日志或测试快照。
- 先失败测试再实现；专项测试通过后才运行全量构建和打包。

---

### Task 1: 暴露源会话合同

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/MessageResponse.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/ThreadService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ConversationMapper.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/message/ThreadServiceWeComSourceConversationTest.java`

**Interfaces:** `MessageResponse` 增加 `sourceConversationId`、`conversationType`、`conversationDisplayName`；ThreadService 对群源会话逐个保留 conversation ID。

- [ ] 写失败测试：同一联系人关联两个 GROUP 源会话时响应包含两个不同 `sourceConversationId`，且群消息不进入联系人直聊 conversation。
- [ ] 运行 `cd demo/message-center-spring/backend && mvn -q -Dtest=ThreadServiceWeComSourceConversationTest test`，确认当前响应缺字段或合并。
- [ ] 实现 mapper 查询源会话元数据并填充响应；保持非 WeCom 构造器兼容。
- [ ] 重新运行专项测试并检查 JSON 字段名称。

### Task 2: 按当前 viewer 计算消息方向

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataStore.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/wecom/WeComChatDataSyncService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/WeComUserBindingMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/ThreadService.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/wecom/WeComDirectionResolverTest.java`

**Interfaces:** 新增纯函数 `WeComDirectionResolver.resolve(viewerWecomUserId, sender, receivers, participants)`，只返回 `inbound|outbound|unknown`；同步上下文携带 viewer ID，线程读取使用当前 CRM 用户绑定的 WeCom ID。

- [ ] 写失败测试：viewer 是员工 A 时 sender A 为 outbound、sender B 且会话含 A 为 inbound；viewer 不在参与者中返回 unknown。
- [ ] 运行专项测试确认当前“sender type=EMPLOYEE 即 outbound”逻辑失败。
- [ ] 实现 resolver，并在 ThreadService 对历史 WeCom 消息按 sender party 与 viewer 重新投影方向；未知方向保留结构化审计原因。
- [ ] 运行 `mvn -q -Dtest=WeComDirectionResolverTest,WeComChatDataStoreTest,WeComMessageProjectorTest test`。

### Task 3: 群会话切换 UI

**Files:**
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/pages/ThreadPage.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComConversationPanel.tsx`
- Create: `demo/message-center-spring/frontend/src/components/wecom/WeComConversationSelector.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/ThreadPage.wecom.test.tsx`

**Interfaces:** 面板接收 `conversations: { id: string; type: 'DIRECT'|'GROUP'; displayName: string; items: MessageResponse[] }[]` 和选中 ID；直聊默认选中，群聊按名称切换。

- [ ] 写失败 UI 测试：两个群会话显示两个入口，切换后只传入目标群消息引用。
- [ ] 运行 `npm run test:ui -- --run src/pages/ThreadPage.wecom.test.tsx src/components/wecom/WeComConversationPanel.test.tsx`。
- [ ] 实现按 `sourceConversationId` 分组和稳定 key，保持非 WeCom 时间线与滚动位置不变。
- [ ] 运行专项 UI 测试。

### Task 4: 单 frame 清空重填与最新请求优先

**Files:**
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComConversationFrame.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/wecom/WeComConversationFrame.test.tsx`

- [x] 写失败测试：A 的 `setData` pending 时切到 B，B 不等待 A；全程只创建一个 frame；A→B→A 只显示最后一代。
- [x] 运行 `npm run test:ui -- --run src/components/wecom/WeComConversationFrame.test.tsx` 确认 RED。
- [x] 保留清空更新，改为 generation-aware latest-wins；切换联系人不 dispose、不新建 frame。
- [x] 运行专项测试并确认连续切换只创建一个 frame。

### Task 5: 回归、构建和 Jar

**Files:**
- Modify: `docs/superpowers/reviews/2026-08-25-wecom-group-direction-frame-verification.md`

- [ ] 后端专项与非 Docker 测试通过；记录 Docker 阻断则不得伪称全量通过。
- [ ] 前端 `npm test`、`npm run build` 和生成资产检查通过。
- [ ] 使用 `mvn -DskipTests package` 仅在测试门禁满足后打包 `backend/target/message-center.jar`，记录 SHA-256。
- [ ] 执行 `unzip -tq` 和服务器只读验收脚本；任何群合并、方向错误或 A→B 空白都停止发布。
