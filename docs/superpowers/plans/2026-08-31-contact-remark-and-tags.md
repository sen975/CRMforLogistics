# 联系人备注与标签恢复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让联系人备注覆盖所有联系人展示名称，并恢复 CRM 联系人标签的读取、编辑和保存链路。

**Architecture:** `ContactService` 负责联系人标签投影与访问控制，标签关系通过专用 MyBatis mapper 读取和事务写入；前端用纯展示名函数统一 `remark -> displayName -> 未命名` 优先级，详情面板通过已有联系人查询和 mutation 管理标签。企业微信通讯录标签继续由 WeCom 管理页独立消费，不与 CRM 标签合并。

**Tech Stack:** Spring Boot、MyBatis-Plus、PostgreSQL/Flyway、React、TypeScript、Ant Design、TanStack Query、Vitest。

## Global Constraints

- 只修改本任务相关文件，保留 worktree 中其他用户改动。
- 不修改 `contacts.display_name` 的语义；备注只作为展示层覆盖值。
- 标签只读取 `contact_tags` / `contact_taggings` 中 `status = 'active'` 的记录。
- 所有写入必须复用联系人访问控制，并在事务内完成。
- 先写失败测试并确认失败，再写生产代码。
- 不使用 `git add .`，每个任务只暂存相关文件。

## 验收记录

- 前端专项：`npm run test:ui -- --run src/components/ContactDetailPanel.test.tsx src/pages/BroadcastsPage.test.tsx`，12/12 通过。
- 前端全量：`npm run test:source` 29/29 通过；`npm run test:ui -- --run` 233/233 通过；`npm run build` 成功。
- 后端专项：`mvn -Dtest=ContactServiceAuthorizationTest,ContactGroupServiceTagsTest,ContactControllerTest test`，12/12 通过。
- 统一会话合同专项：`mvn -Dtest=UnifiedConversationServiceTest test`，2/2 通过。
- 后端全量：当前 worktree 的既有 `TopicAiResponseParserTest` 有 1 个失败，且 22 个集成测试因本机 Docker/socket 权限无法启动；不属于本任务调用链，未修改。
- 本地真实数据库 API 验证尚未执行，需要在本地后端和 PostgreSQL 已启动且有登录会话时验证。

### Task 1: 后端标签投影合同

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactTagResponse.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactTagMapper.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactResponse.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactService.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ContactServiceAuthorizationTest.java`

**Interfaces:**
- `ContactTagResponse(UUID id, String name, String color)`。
- `ContactTagMapper.findActiveByContactId(UUID contactId): List<ContactTagResponse>`，按 `lower(name), id` 排序。
- `ContactResponse` 增加 `List<ContactTagResponse> tags` 字段，位于 `identities` 之前。

- [x] **Step 1: Write the failing test**

在 `ContactServiceAuthorizationTest` 增加一个联系人响应断言，构造带标签的 mapper 返回值并断言 `response.tags()` 返回标签；同时断言现有联系人响应构造需要新的 `tags` 字段。

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
cd demo/message-center-spring/backend
./mvnw -Dtest=ContactServiceAuthorizationTest test
```

Expected: FAIL，原因是 `ContactResponse` 没有 `tags` 字段或 `ContactService` 没有读取标签。

- [x] **Step 3: Write minimal implementation**

新增 `ContactTagResponse` record 和 mapper：

```java
@Mapper
public interface ContactTagMapper {
    @Select("select t.id, t.name, t.color from contact_taggings ct "
          + "join contact_tags t on t.id = ct.tag_id "
          + "where ct.contact_id = #{contactId} and t.status = 'active' "
          + "order by lower(t.name), t.id")
    List<ContactTagResponse> findActiveByContactId(@Param("contactId") UUID contactId);
}
```

在 `ContactService` 注入 mapper，并在 `toResponse` 中读取标签后传入 `ContactResponse`。

- [x] **Step 4: Run test to verify it passes**

Run the same Maven command. Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactTagResponse.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactTagMapper.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactResponse.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactService.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ContactServiceAuthorizationTest.java
git commit -m "feat: project contact tags"
```

### Task 2: 后端标签替换接口

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/ContactTagsRequest.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactController.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ContactControllerTest.java` (create if absent)

**Interfaces:**
- `PUT /api/contacts/{id}/tags` with `{ "tags": [{ "name": string, "color": string|null }] }`。
- `ContactGroupService.updateTags(UUID contactId, List<ContactTagInput> tags, UUID actorId): void`。
- 写入前调用现有 `findAccessibleById`/等价授权检查；事务内删除关系、按大小写不敏感名称复用或创建活动标签，再插入关系。

- [x] **Step 1: Write the failing test**

增加 Web 层测试：带一个标签的 PUT 返回 200；空数组表示清空；不可访问联系人返回现有未授权错误。

- [x] **Step 2: Run test to verify it fails**

```bash
cd demo/message-center-spring/backend
./mvnw -Dtest=ContactControllerTest test
```

Expected: FAIL，原因是路由与服务方法尚不存在。

- [x] **Step 3: Write minimal implementation**

`ContactTagsRequest` 使用嵌套 record 校验名称，controller 调用 service；service 使用数据库事务和参数化 SQL 完成替换，重复名称按小写去重，空名称忽略，名称超过 100 字符返回统一参数错误。

- [x] **Step 4: Run test to verify it passes**

运行同一命令，Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/ContactTagsRequest.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupService.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactController.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/web/ContactControllerTest.java
git commit -m "feat: update contact tags"
```

### Task 3: 前端展示名与标签编辑

补充：统一会话列表的联系人项也返回 `remark`，由 `ConversationMapper` 投影，确保左侧联系人入口与详情页使用同一展示优先级；企业微信群项的 `remark` 保持为空。

**Files:**
- Create: `demo/message-center-spring/frontend/src/utils/contactDisplayName.ts`
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts`
- Modify: `demo/message-center-spring/frontend/src/hooks/useContacts.ts`
- Modify: `demo/message-center-spring/frontend/src/components/ContactCard.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/ContactDetailPanel.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/ThreadPage.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/SendPage.tsx`
- Test: `demo/message-center-spring/frontend/src/utils/contactDisplayName.test.ts`
- Test: `demo/message-center-spring/frontend/src/components/ContactDetailPanel.test.tsx`

**Interfaces:**
- `contactDisplayName(contact: { remark?: string|null; displayName?: string|null }): string`。
- `ContactTag { id: string; name: string; color?: string|null }`，加入 `ContactResponse.tags`。
- `updateContactTags(id, tags): Promise<void>` 调用 `PUT /contacts/{id}/tags`。

- [x] **Step 1: Write the failing tests**

测试 `contactDisplayName`：备注和原始昵称同时存在返回备注，备注为空返回原始昵称，两者为空返回“未命名”。详情测试带标签联系人时显示标签，保存标签调用 PUT 并刷新查询。

- [x] **Step 2: Run tests to verify they fail**

```bash
cd demo/message-center-spring/frontend
npm test -- --run src/utils/contactDisplayName.test.ts src/components/ContactDetailPanel.test.tsx
```

Expected: FAIL，当前展示顺序为 `displayName || remark`，且没有联系人标签字段/编辑控件。

- [x] **Step 3: Write minimal implementation**

新增展示名 helper，并替换四个入口中的内联逻辑。详情面板增加标签查看/编辑行，使用 Ant Design `Select` 的 `mode="tags"`；保存成功后失效 `contact`、`contacts`、`conversations` 查询，失败时保留草稿。标签颜色使用后端返回值，缺省使用默认 Tag 颜色。

- [x] **Step 4: Run tests to verify they pass**

```bash
npm test -- --run src/utils/contactDisplayName.test.ts src/components/ContactDetailPanel.test.tsx
npm run build
```

Expected: 两个测试文件 PASS，生产构建成功。

- [ ] **Step 5: Commit**

```bash
git add demo/message-center-spring/frontend/src/utils/contactDisplayName.ts demo/message-center-spring/frontend/src/utils/contactDisplayName.test.ts demo/message-center-spring/frontend/src/api/types.ts demo/message-center-spring/frontend/src/api/endpoints.ts demo/message-center-spring/frontend/src/hooks/useContacts.ts demo/message-center-spring/frontend/src/components/ContactCard.tsx demo/message-center-spring/frontend/src/components/ContactDetailPanel.tsx demo/message-center-spring/frontend/src/components/ContactDetailPanel.test.tsx demo/message-center-spring/frontend/src/pages/ThreadPage.tsx demo/message-center-spring/frontend/src/pages/SendPage.tsx
git commit -m "feat: restore contact labels and remark display"
```

### Task 4: 集成验收与文档回写

**Files:**
- Modify: `demo/message-center-spring/README.md`
- Modify: `docs/superpowers/README.md`

- [x] **Step 1: Run backend专项测试**

```bash
cd demo/message-center-spring/backend
./mvnw -Dtest=ContactServiceAuthorizationTest,ContactControllerTest test
```

- [x] **Step 2: Run frontend全量测试与构建**

```bash
cd demo/message-center-spring/frontend
npm test -- --run
npm run build
```

- [ ] **Step 3: Verify API contract locally**

启动本地后端后，用已认证会话请求 `GET /api/contacts/{id}`，确认响应含 `tags`；调用 `PUT /api/contacts/{id}/tags` 后再次 GET，确认标签替换和备注展示字段不变。

- [x] **Step 4: Update docs** (未提交，保留给用户按需精确 stage)

在 README 的联系人 API 部分补充标签字段与 PUT 路由，在 superpowers 索引中登记设计和计划文档，然后仅提交这两个文档文件。

```bash
git add demo/message-center-spring/README.md docs/superpowers/README.md
git commit -m "docs: document contact labels"
```
