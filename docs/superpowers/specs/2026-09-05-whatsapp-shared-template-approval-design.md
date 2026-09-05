# WhatsApp 共享模板与变更审批设计

## 真源与覆盖关系

本文是 WhatsApp 模板身份、共享范围、变更权限和审批流程的当前最高层真源。它覆盖 `2026-09-04-user-channel-address-books-design.md` 中“所有登录用户均可直接修改、停用、删除模板和调整发送权限”的旧规则；该文档的联系人、渠道账号和 owner 隔离设计继续有效。

## 目标

WhatsApp 模板是同一 CAMS `custSpaceId` 下的系统级共享资产。所有登录用户都能查看和使用可发送模板，也能直接提交新模板到官方审核；对已有模板的修改必须受内部审批约束，避免普通用户直接改变所有人的共享资产。

本设计实现：

- 同一个上游模板在本地只有一条共享记录，不按渠道账号复制模板所有权。
- 普通用户可以直接申请新模板，但修改已有模板时只能提交内部变更申请。
- 管理员可以批准或拒绝普通用户的申请，也可以直接执行模板变更。
- 模板可见性与用户渠道凭证分离：模板共享，AK/SK、发送号码和账号配置仍按用户隔离。
- 每次上游调用、审批和执行都有明确操作者、凭证来源、版本与审计记录。

## 产品边界

- 不设置“我的模板”和“别人的模板”，模板不拥有 `owner_user_id`。
- `created_by_user_id` 只表示最初申请人，不产生模板所有权或独占修改权。
- 管理员只在模板审批和直接变更范围内拥有治理权限；这不能成为读取其他用户联系人、消息、附件、Topic、群发任务或渠道密钥的旁路。
- 新模板申请直接提交阿里云 CAMS/WhatsApp 官方审核，不经过内部管理员审批。
- 同步是只读操作，不需要内部审批。
- 普通用户不能直接修改、停用、删除已有模板或调整其发送权限。
- 管理员直接执行模板变更时不需要另一名管理员复核。
- 所有参与共享模板的 WhatsApp 账号必须属于同一个 CAMS `custSpaceId`。检测到不同空间时阻断迁移与变更，不能跨空间合并模板。
- 旧账号级模板 API 不保留为并行业务入口；迁移完成后必须退出，避免绕过审批。

## 身份与唯一 Owner

### 共享模板身份

共享模板的业务身份为：

```text
provider_scope_id + provider_template_id + language_code
```

`provider_scope_id` 指向一个已验证的 CAMS `custSpaceId`。系统不得把 AK/SK 或发送号码写入模板记录。模板目录由 `SharedWhatsAppTemplateService` 唯一拥有，Controller、前端、同步 worker 和 Gateway 不得另行推断模板身份或权限。

### 用户、账号与操作者

- 当前用户来自服务端认证上下文。
- `channel_accounts.owner_user_id` 决定谁可以使用某组 WhatsApp 凭证。
- 新模板申请与管理员直接操作使用当前用户唯一有效的 WhatsApp 账号。
- 普通用户变更申请保存 `requested_via_account_id`。管理员批准后仍使用该账号的加密凭证执行，不读取或展示明文。
- 申请账号在批准前解绑、失效或离开目标 provider scope 时，执行进入结构化失败，不静默换用其他用户凭证。
- `requested_by_user_id`、`reviewed_by_user_id` 和实际执行者分别记录申请、审批和执行身份，不承担模板所有权。

## 数据模型

### Provider Scope

建立共享模板空间实体，至少包含：

```text
id
provider
external_scope_id
status
created_at
updated_at
```

`provider + external_scope_id` 唯一。`channel_accounts` 显式关联 `provider_scope_id`。已有账号的 scope 由应用解密账号配置后回填；数据库迁移不得尝试解析加密配置。

### 共享模板

`message_templates` 从账号投影升级为共享模板真源，至少保留：

```text
id
provider_scope_id
provider_template_id
language_code
name
remark
body
status
category
components_jsonb
examples_jsonb
message_send_ttl_seconds
allow_send
provider_audit_status
provider_updated_at
last_synced_at
created_by_user_id
version
deleted_at
```

`provider_scope_id + provider_template_id + language_code` 建立唯一约束。`created_by_user_id` 可空且只用于审计。模板可发送性只来自共享记录的官方状态、`allow_send` 和删除状态。

### 模板变更申请

新增 `template_change_requests`：

```text
id
template_id
change_type
requested_payload_jsonb
base_version
requested_by_user_id
requested_via_account_id
status
idempotency_key
reviewed_by_user_id
review_reason
reviewed_at
execution_started_at
execution_completed_at
execution_error_code
execution_error_message
provider_request_id
created_at
updated_at
```

`change_type` 只接受：

```text
MODIFY
SET_SEND_PERMISSION
DELETE
BIND_MEDIA
```

普通用户请求状态机为：

```text
PENDING_APPROVAL
  -> REJECTED
  -> STALE
  -> EXECUTING
       -> SUCCEEDED
       -> EXECUTION_FAILED
```

每个申请人的 `idempotency_key` 唯一。批准前必须比较 `base_version`；不一致时转为 `STALE`，不能覆盖新版本。一个共享模板同一时刻最多执行一个变更。

### 媒体与操作审计

媒体上传记录继续保存上传用户和凭证账号，只是临时素材，不产生模板所有权。上传本身不修改共享模板；普通用户把素材绑定到已有模板时必须提交 `BIND_MEDIA` 或包含媒体差异的 `MODIFY` 申请。新模板申请可以直接携带已上传媒体提交官方审核。

现有 `template_operations` 保留为不可变执行审计，关联共享模板和可选的变更申请，记录操作者、凭证账号、上游请求 ID、请求摘要、结果与错误。它不参与授权判断。

## 权限矩阵

| 操作 | 普通用户 | 管理员 |
| --- | --- | --- |
| 查看、搜索、发送模板 | 直接执行 | 直接执行 |
| 同步模板 | 直接执行 | 直接执行 |
| 申请新模板 | 直接提交官方审核 | 直接提交官方审核 |
| 修改已有模板 | 提交内部审批 | 直接执行 |
| 设置发送权限 | 提交内部审批 | 直接执行 |
| 停用或删除模板 | 提交内部审批 | 直接执行 |
| 修改模板并绑定媒体 | 提交内部审批 | 直接执行 |
| 批准、拒绝、重试申请 | 禁止 | 允许 |

所有权限在服务端执行。前端隐藏按钮不是授权边界。管理员直接执行仍要进行影响确认、乐观锁、幂等和完整审计。

## 服务与数据流

### 新模板申请

1. 服务端从当前登录用户解析唯一有效 WhatsApp 账号并验证 provider scope。
2. 校验模板内容和媒体上界。
3. 使用该账号的加密凭证直接提交官方审核。
4. 写入或刷新唯一共享模板，并记录 `created_by_user_id` 和操作审计。
5. 官方接受请求不等于审核通过；共享模板保持真实官方审核状态。

### 普通用户修改已有模板

1. 读取共享模板和当前 `version`。
2. 验证当前用户拥有 `requested_via_account_id`，且账号位于模板 provider scope。
3. 保存结构化变更 payload、字段差异和 `base_version`。
4. 返回 `PENDING_APPROVAL`，不调用上游、不改变共享模板。

### 管理员审批

1. 管理员查看模板新旧字段差异、媒体预览、申请人和影响范围。
2. 拒绝时必须填写原因，状态转为 `REJECTED`。
3. 批准时锁定申请和模板，校验申请仍待审批、版本未变化、凭证账号仍有效。
4. 状态转为 `EXECUTING` 后调用上游。
5. 成功后读取官方详情，更新共享模板并递增版本；失败写入结构化错误。
6. 只有已批准且执行失败的申请可由管理员重试；重试仍执行版本和账号校验。

### 管理员直接修改

管理员跳过审批申请，使用自己的有效 WhatsApp 账号直接执行。服务仍必须锁定模板、检查版本、写操作审计并在成功后刷新官方详情。管理员没有有效账号时不能借用任意用户账号直接操作。

### 同步与发送

同步使用发起者自己的账号凭证读取目标 provider scope，并更新唯一共享模板目录。定时同步只需选择该 scope 中一个健康账号执行一次；账号失败时可尝试同 scope 的下一个健康账号，但必须记录实际凭证账号，且不能跨 scope。

发送时读取共享模板内容和可发送状态，再使用当前用户自己的 WhatsApp 账号发送。模板共享不得暴露或复用其他用户的账号凭证、号码、联系人或会话。

## API 合同

共享模板业务 API：

```text
GET    /api/v1/whatsapp/templates
GET    /api/v1/whatsapp/templates/{templateId}
POST   /api/v1/whatsapp/templates/applications
POST   /api/v1/whatsapp/templates/sync
POST   /api/v1/whatsapp/templates/{templateId}/change-requests
GET    /api/v1/whatsapp/template-change-requests/mine
```

管理员审批 API：

```text
GET  /api/v1/admin/whatsapp/template-change-requests
POST /api/v1/admin/whatsapp/template-change-requests/{requestId}/approve
POST /api/v1/admin/whatsapp/template-change-requests/{requestId}/reject
POST /api/v1/admin/whatsapp/template-change-requests/{requestId}/retry
```

普通用户不能通过请求体伪造申请人、审批人、管理员直执行标识、provider scope 或账号 owner。服务端根据认证上下文和已拥有账号填充这些字段。旧的 `/api/v1/channel-accounts/{accountId}/whatsapp/templates/**` 账号级模板入口在迁移完成后删除。

## 前端体验

模板页展示系统级共享目录，不显示账号归属或账号选择器。

普通用户：

- “新建模板”直接进入官方申请表单。
- “使用模板”进入发送界面。
- “修改”“发送权限”“停用”“删除”打开变更申请窗口。
- “我的申请”展示等待审批、执行中、成功、拒绝、过期和执行失败状态。
- 被拒绝或过期的申请可基于当前模板重新编辑并提交，不能直接恢复旧 payload。

管理员：

- 直接变更前显示“影响所有用户”的确认窗口。
- 增加“变更审批”入口。
- 审批详情按字段展示新旧差异，不直接展示原始 JSON。
- 拒绝必须填写原因。
- 已过期申请只能查看，批准按钮禁用。
- 已批准但执行失败的申请提供重试按钮。

用户可见状态统一为：等待审批、已批准正在执行、执行成功、已拒绝、模板版本已变化、执行失败。

## 迁移与切换

迁移分为以下阶段：

1. 创建 provider scope、共享模板和变更申请 schema，但暂不开放新入口。
2. 应用逐账号解密配置并回填 `provider_scope_id`，只记录 scope，不记录明文凭证。
3. 若检测到多个不同 `custSpaceId`，停止模板迁移并输出结构化对账结果。
4. 在目标 scope 内按模板业务身份归并账号副本。
5. 调用官方同步，以官方当前详情覆盖本地副本冲突，不根据本地更新时间猜测内容。
6. 重连历史操作和媒体引用；无法识别的记录进入迁移异常集合，不进入可发送目录。
7. 对账唯一模板数、审核状态、发送权限、操作引用、媒体引用和孤儿记录。
8. 对账通过后切换共享 API，删除旧账号级业务入口和副本约束。

切换不保留新旧双写或长期兼容路径。旧副本只能在迁移回滚窗口内作为只读备份，不能继续被业务读取。

## 错误、并发与资源上界

- 所有创建、申请、审批和执行命令使用有界幂等键。
- payload、字段数量、模板正文、媒体大小、列表页大小和审批批次都有上界。
- 上游超时进入可重试执行失败，不写成功模板状态。
- 明确的上游业务拒绝进入终态失败，只有重新提交新申请才能再次执行。
- 单个申请失败不能阻塞其他模板。
- 删除、停用、发送权限和内容修改成功后立即失效共享模板缓存。
- 错误返回结构化 code、traceId 和可公开原因，不回显凭证、上游密钥或原始敏感 payload。
- 审批 worker 使用有界租约和最大重试次数，不允许任务永久占据队列头部。

## 验收标准

1. 所有登录用户看到相同的共享模板目录，并可使用所有官方允许发送的模板。
2. 同一 provider scope、模板代码和语言在数据库中只有一条共享模板记录。
3. 普通用户可以直接提交新模板到官方审核。
4. 普通用户修改、停用、删除、调整发送权限或绑定媒体时不会直接调用上游，只产生待审批申请。
5. 管理员可以批准或拒绝普通用户申请，也可以直接执行模板变更。
6. 拒绝必须有原因；旧版本申请不能覆盖新模板；账号失效时不换用其他用户凭证。
7. 同步只读取上游并更新共享目录，不创建账号级模板副本。
8. 发送使用当前用户账号凭证，模板共享不会扩大联系人、消息、附件或渠道密钥访问范围。
9. 迁移在不同 `custSpaceId`、重复模板、孤儿操作或媒体引用未对账时阻止切换。
10. 后端覆盖权限矩阵、状态机、幂等、乐观锁、凭证失效、上游失败、迁移和跨用户安全测试。
11. 前端覆盖普通用户申请、管理员审批与直接执行、差异预览、拒绝原因、过期状态和失败重试。
12. 发布前通过后端全量测试、前端全量测试、生产构建、Jar/ZIP 校验和真实 CAMS 测试账号验收。

## 非目标

- 不建立模板个人所有权、模板转让或按用户复制模板。
- 不允许普通用户通过自己账号直接治理共享模板。
- 不把管理员角色扩展到用户私有联系人、消息、附件、Topic 或渠道配置。
- 不支持多个 CAMS provider scope 在同一共享目录中混用。
- 不长期保留账号级模板 API 或双写兼容层。
