# 管理员平台工作台与多 CAMS 设计

## 背景与目标

当前 CAMS 配置、WhatsApp 账号管理和模板审批分别嵌在渠道设置页、渠道设置页中的账号区以及普通模板页 Tab 中。管理员治理能力与普通用户操作混在一起，且 CAMS 配置按单例使用，无法表达多个客户空间及其号码边界。

本设计建立管理员专用工作台，统一管理平台接入、WhatsApp 号码分配和模板变更审批；普通用户继续在模板工作台提交申请和查看自己的申请。CAMS 以可管理实例集合存在，账号、模板和审批申请均绑定到明确的 CAMS scope。

## 产品边界

### 做什么

- 新增管理员总览和三个独立管理路由：平台、WhatsApp 账号、模板审批。
- 支持多个 CAMS 实例，每个实例独立保存加密凭证、连接状态和同步状态。
- 账号管理按 CAMS 分层，支持同步、分配、转交、收回和分配历史。
- 模板审批独立成管理员队列，支持批准、拒绝、失败重试和结构化差异查看。
- 普通模板页保留共享模板、提交变更申请和“我的申请”。

### 不做什么

- 不新增独立后台应用、第二套登录或第二套权限系统。
- 不允许前端绕过后端权限，也不在前端推断账号、模板或审批状态。
- 不物理删除已经被账号或模板引用的 CAMS scope。
- 不跨 CAMS 替换账号、凭证或模板审批执行上下文。

## 信息架构与路由

- `/admin`：管理员总览。聚合每个 CAMS 的连接状态、可用号码数、待审批数、最近同步时间和失败提示，只做导航和概览，不直接修改业务数据。
- `/admin/platforms`：CAMS 实例列表与配置。支持新增、编辑、停用、测试连接和按实例同步。
- `/admin/whatsapp/accounts`：WhatsApp 账号管理。先选择 CAMS，再查看该空间号码并执行分配、转交、收回、查看历史。
- `/admin/whatsapp/template-approvals`：模板变更审批队列。按状态、申请人、模板名称和提交时间筛选，查看差异并审批。
- `/templates`：普通用户模板工作台。保留共享模板、公共模板库和“我的申请”；移除管理员审批 Tab。
- `/settings/channels`：保留邮件、企业微信等通用渠道设置；迁出 CAMS 配置和 WhatsApp 管理区。

管理员导航只在 `isAdmin` 为真时显示；未授权访问管理员路由由服务端返回 403，前端显示无权限状态，不依赖隐藏按钮作为安全边界。

## 多 CAMS 数据与生命周期

现有 `whatsapp_provider_scopes` 作为 CAMS 实例的唯一 owner。每个 scope 代表一个 `provider + external_scope_id(CustSpaceId)`，并保存：

- 管理显示名称；
- CustSpaceId；
- 加密后的 AccessKey ID/Secret、Region、Endpoint；
- `READY/BLOCKED` 状态；
- 最近连接测试、最近同步和结构化错误投影（必要时新增字段或审计记录）。

同一 provider 与 CustSpaceId 唯一。已被 `channel_accounts` 或 `message_templates` 引用的 scope 只能停用，不能物理删除。数据库已有配置时，测试和同步始终使用该 scope 的解密配置；只有尚未配置任何 scope 时，系统才可按既有环境配置显示“未配置”，不得静默混用多个来源。

推荐的管理 API：

```text
GET    /api/admin/whatsapp/cams
POST   /api/admin/whatsapp/cams
PUT    /api/admin/whatsapp/cams/{scopeId}
POST   /api/admin/whatsapp/cams/{scopeId}/test
POST   /api/admin/whatsapp/cams/{scopeId}/sync
GET    /api/admin/whatsapp/cams/{scopeId}/accounts
```

凭证响应只返回脱敏 AccessKey ID 和非敏感字段。更新时 Secret 可留空表示保留旧值；显式清除凭证必须走服务端校验和审计，不提供普通表单上的隐式清除语义。

## WhatsApp 账号管理

账号页面采用 scope-first 交互：

1. 选择一个 CAMS 实例；
2. 读取该 scope 下状态为可用的号码；
3. 选择号码并分配给销售，或对已有归属执行转交/收回；
4. 在同一 scope 范围查看分配历史和同步结果。

当 scope 只有一个可用号码时，页面自动选中该号码并隐藏多余的号码下拉框，分配按钮直接进入“选择销售”；当有多个号码时，必须明确选中一个号码后才能执行。所有写操作携带并校验账号版本与 scopeId，冲突时拒绝本次操作、刷新列表并提示管理员重试。

号码同步只导入当前 scope 的 CAMS 号码，并将不可用号码标记为不可发送，不删除历史分配记录。分配、转交、收回均记录操作者、目标用户、原因、版本和时间。

## 模板审批工作台

管理员队列优先展示“待审批、执行中、执行失败”，并支持状态、申请人、模板和时间筛选。申请详情展示模板版本、变更类型、申请人、提交时间、结构化字段差异和当前状态，不渲染原始 JSON 或凭证。

- 批准前原子检查模板版本等于申请基线版本并认领申请；随后使用申请时绑定的账号和 scope 调用 CAMS 官方 API。
- 拒绝必须填写原因；申请进入 `REJECTED`。
- 只有 `EXECUTION_FAILED` 可重试，继续使用原账号和 scope；不能替换凭证上下文。
- 版本变化进入 `STALE`，禁止批准，要求申请人重新提交。
- 上游提交结果不确定时保持 `EXECUTING`，由 reconcile worker 在有界重试窗口内收敛，避免重复提交。
- 所有审批和执行结果记录审批人、时间、原因、provider request id（按现有脱敏策略）及结构化错误。

普通用户在 `/templates` 的“我的申请”中看到同样的状态和差异，但没有批准、拒绝、重试操作。管理员在普通模板页进行共享模板直接变更时，仍需确认“影响所有用户”，但审批处理入口统一迁移到管理员审批页。

## 权限与后端边界

管理员 API 统一使用服务端授权检查，至少覆盖 `ROLE_ADMIN` 与项目现有 admin 角色映射，最终以 `RoleMapper.userHasRole` 等既有真源统一。管理员总览、平台、账号和审批接口均拒绝非管理员访问。

业务 owner 保持单一：

- `WhatsAppCamsConfigService`：scope 配置和凭证生命周期；
- `AdminWhatsAppAccountSyncService`：按 scope 同步号码；
- 账号分配服务与审计表：归属状态；
- `WhatsAppTemplateChangeRequestService`：申请状态机和执行上下文；
- React 页面：路由、展示、表单和 API 接线，不拥有业务状态机。

## 错误处理与可观测性

- 配置缺失、scope 停用、凭证解密失败、CAMS 连接失败、账号版本冲突和模板版本过期均返回结构化错误码与用户可理解的中文提示。
- 不在日志、响应或前端状态中输出 AccessKey Secret、完整授权码或其他敏感凭证。
- 同步和审批结果提供可追踪的时间、数量、状态和错误原因；未知上游结果不伪装为成功或失败。
- 所有列表、分页、批量同步和重试均有明确上界，避免无界查询或重复提交。

## 验收标准

### 后端

- 非管理员访问所有 `/api/admin/whatsapp/**` 和管理员模板审批接口返回 403。
- 可创建、编辑、停用多个 CAMS；重复 CustSpaceId 被拒绝。
- Secret 不回显；更新其他字段时可保留旧 Secret。
- 测试和同步按指定 scope 使用数据库配置，不错误回退到环境变量。
- 账号列表严格按 scope 隔离；单号码自动选中，多号码必须选择；并发版本冲突可检测。
- 模板审批批准、拒绝、重试、STALE、EXECUTING 收敛行为符合状态机。

### 前端

- 管理员导航显示管理员中心，非管理员不显示。
- 平台、账号、审批三个路由可直接访问并在刷新后保持上下文。
- 单号码和多号码账号选择交互符合上述规则。
- Secret 输入框不回显旧值；错误、加载、空态和无权限状态清晰可见。
- 普通模板页不再出现管理员审批 Tab；“我的申请”仍可用。

### 命令门禁

```bash
cd demo/message-center-spring/frontend
npm run test:ui
npm run build

cd ../backend
mvn test
mvn -q -DskipTests compile
```

真实验收还需在后端 8107、前端开发服务器运行时检查管理员登录、多个 CAMS、单号码/多号码选择以及模板审批按钮和错误状态。

## 未闭合风险

- 需要在实现前确认项目当前管理员 authorities 与 `RoleMapper` 的统一方式，并补充权限契约测试。
- `whatsapp_provider_scopes` 当前字段是否足以记录连接测试和同步投影，需要根据现有迁移决定新增字段还是独立审计表。
- 共享模板当前按 scope 隔离；若未来需要跨 scope 的全局模板目录，必须另行设计，不在本次范围内。
