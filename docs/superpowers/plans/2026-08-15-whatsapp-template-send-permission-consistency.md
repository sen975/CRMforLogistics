# WhatsApp 模板发送权限一致性修复实施计划

> **执行要求：** 必须使用 `subagent-driven-development`（推荐）或 `executing-plans` 按任务逐项实施，并用复选框（`- [ ]`）跟踪步骤状态。

**目标：** 修复 CAMS 模板启停写操作成功后被旧详情快照覆盖，导致前端按钮看似无效的问题。

**架构：** `WhatsAppTemplateApplicationService` 继续拥有模板管理命令和本地投影更新语义。`setSendPermission` 在写接口成功后以 `PropertyResult.allowSend` 更新当前锁定实体，不再在同一命令内读取详情；既有同步与对账服务负责后续上游最终状态对账。

**技术栈：** Java 21、Spring Boot、MyBatis-Plus、JUnit 5、Mockito、AssertJ、Maven

## 全局约束

- 不修改前端按钮、HTTP 合同、数据库 schema、CAMS gateway 或其他模板命令流程。
- CAMS 写接口失败时，本地 `allowSend` 不得变化。
- CAMS 写接口成功时，写结果必须立即成为本地投影的当前值。
- 工作区存在大量其他改动，只允许暂存本任务明确列出的文件。

---

### 任务 1：修复模板发送权限即时投影

**文件：**
- 修改：`demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationService.java:137-169`
- 测试：`demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationServiceTest.java`

**接口：**
- 输入：`WhatsAppTemplateGateway.setSendPermission(UUID, String, String, boolean)` 返回 `PropertyResult`
- 输出：`WhatsAppTemplateApplicationService.setSendPermission(...)` 成功后把 `PropertyResult.allowSend` 写入 `TemplateEntity.allowSend`

- [x] **Step 1: 写入能复现旧快照覆盖的失败测试**

```java
@Test
void sendPermissionWriteResultWinsOverStaleProviderDetail() {
    TemplateEntity template = template("tpl-1", "APPROVED", false);
    when(templateMapper.selectOne(any())).thenReturn(template);
    when(operationMapper.findByIdempotency(ACCOUNT_ID, "client-enable"))
            .thenReturn(Optional.empty());
    when(operationMapper.insertIgnore(any())).thenReturn(1);
    when(operationMapper.findByIdForUpdate(any())).thenReturn(Optional.empty());
    when(gateway.setSendPermission(ACCOUNT_ID, "tpl-1", "en_US", true))
            .thenReturn(new PropertyResult(true, "req-enable"));
    when(gateway.detail(ACCOUNT_ID, "tpl-1", "en_US"))
            .thenReturn(Optional.of(snapshot("tpl-1", ReviewStatus.APPROVED, false)));

    service.setSendPermission(ACCOUNT_ID, "tpl-1", "en_US", true,
            "client-enable", ACTOR_ID, "trace-enable");

    ArgumentCaptor<TemplateEntity> updated = ArgumentCaptor.forClass(TemplateEntity.class);
    verify(templateMapper).updateById(updated.capture());
    assertThat(updated.getValue().getAllowSend()).isTrue();
}
```

- [x] **Step 2: 运行测试并确认红灯**

运行：

```bash
cd demo/message-center-spring/backend
mvn -Dtest=WhatsAppTemplateApplicationServiceTest#sendPermissionWriteResultWinsOverStaleProviderDetail test
```

预期：失败，捕获到的 `TemplateEntity.allowSend` 为 `false`。

- [x] **Step 3: 实现写结果优先的最小修复**

将 `setSendPermission` 成功分支改为：

```java
PropertyResult result = gateway.setSendPermission(accountId, templateCode, language, allowSend);
current.setAllowSend(result.allowSend());
current.setUpdatedAt(now());
templateMapper.updateById(current);
```

审计日志的 `after.allowSend` 使用 `result.allowSend()`，不再调用 `detailAfterAcknowledgedWrite`。

- [x] **Step 4: 运行针对性测试并确认绿灯**

运行：

```bash
cd demo/message-center-spring/backend
mvn -Dtest=WhatsAppTemplateApplicationServiceTest test
```

预期：`WhatsAppTemplateApplicationServiceTest` 全部通过，无警告或错误。

- [x] **Step 5: 运行模板相关回归测试**

运行：

```bash
cd demo/message-center-spring/backend
mvn -Dtest=WhatsAppTemplateApplicationServiceTest,WhatsAppTemplateReconciliationServiceTest,WhatsAppTemplateControllerTest test
```

预期：三个测试类全部通过，无警告或错误。

- [x] **Step 6: 复核任务 diff 和工作区边界**

运行：

```bash
git diff --check -- demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationService.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/whatsapp/template/WhatsAppTemplateApplicationServiceTest.java docs/superpowers/plans/2026-08-15-whatsapp-template-send-permission-consistency.md
git status --short
```

预期：`git diff --check` 无输出；本任务仅新增计划并修改上述两个 Java 文件，其他脏文件保持原状。
