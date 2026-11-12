# WhatsApp 管理员配置与销售账号分配设计

## 文档状态

当前真源。本设计取代 `2026-09-09-whatsapp-cams-embedded-signup-self-service-design.md` 中的管理员 WABA Embedded Signup、员工 Business App 自助绑定和员工自助添加 API 电话路线。旧文档仅作为历史实现记录，不再定义当前产品行为。

## 目标

管理员先在阿里云 CAMS 控制台手工完成 WABA 和电话号码配置，再在 CRM 管理页面导入、校验并分配这些已有 WhatsApp 发送账号。一个 WhatsApp 电话同一时刻只分配给一名销售；管理员可以收回并转交给另一名销售。

账号转交只改变当前发送权限，不删除、复制或改写历史联系人、会话、消息、附件、模板和审计。原销售保留自己服务过的历史会话只读权限，新销售获得该账号完整既有会话的访问权并继续处理后续消息。

## 产品边界

- 不调用 `IsvGetAppId`、`GetPermissionByCode` 或 `ChatappBindWaba`，不打开 Meta Embedded Signup。
- 不允许销售自行创建、绑定、解绑或更换 WABA、CAMS scope 和发送号码。
- 不在 CRM 中购买、创建或注销运营商电话号码；WABA 和号码生命周期由管理员在 CAMS 控制台管理。
- CRM 不删除 CAMS、Meta、WABA、电话号码或模板资源。收回只改变 CRM 分配状态。
- 当前只支持一个 WhatsApp 电话同时分配给一名销售，不支持多销售共享同一发送账号。
- 管理员可以查看和管理全部 WhatsApp 账号及其分配历史，但不能用页面回显 CAMS AccessKey Secret。
- 销售只看到当前分配给自己的发送账号；历史只读访问通过会话授权表达，不把已收回账号重新显示为可发送账号。
- 企业 API 模板继续属于同一 CAMS scope 的共享资产，账号转交不迁移、不复制模板。

## 唯一真源与职责

`channel_accounts` 是 CAMS 电话账号的稳定本地身份，记录不因分配、收回或转交而重建。`channel_accounts.owner_user_id` 表示当前销售归属，只用于当前账号管理和发送授权，不表示消息作者，也不决定全部历史可见性。

`conversations.assigned_user_id`、团队成员关系和 `conversation_access_grants` 共同构成会话访问真源。账号转交服务负责为新销售建立该账号既有会话的永久访问授权；原销售已获得的会话分配或授权不撤销，因此仍可只读查看历史。

`messages.channel_account_id` 永久指向实际发送或接收该消息的稳定账号；`messages.created_by_user_id` 记录出站消息的实际操作者。转交时禁止修改这两个字段，因此历史消息仍显示原发送人。

`whatsapp_account_assignment_audits` 记录分配、收回和转交的前后 owner、操作者、原因与时间。Controller、前端和 CAMS adapter 不得自行推断归属或修改 owner。

## 管理员配置与账号发现

管理员配置页使用服务端 CAMS 凭据和已知 `custSpaceId` 调用公开只读接口同步电话号码。只允许把 CAMS 返回且满足可发送条件的号码导入 CRM：

- 号码标准化后全局唯一；
- provider 状态为 `ACTIVE` 或 CAMS 当前等价可发送状态；
- 验证状态为 `VERIFIED`；
- 属于当前配置的企业 CAMS scope；
- 本地凭据可解密且只在后端使用。

同步复用现有 `channel_accounts` 记录，不根据显示名创建重复账号。CAMS 中消失或不可发送的号码标记为 provider 不可用，停止新发送，但保留本地账号和历史消息。管理员可以重新同步恢复状态。

同步是唯一能恢复本地不可用账号的通路。CAMS 返回某号码可发送时，同步把同一行（含被旧解绑逻辑写成 `auth_status = 'disabled'` 的行）恢复为 `auth_status = 'active'` 并刷新 provider 状态，不新建行、不改 `owner_user_id`、不动消息与会话。页面只展示 provider 与验证状态，因此管理员看到的"服务商状态可发送"必须与账号真的可用于发送一致；本地 `auth_status` 不得长期与 CAMS 结果背离。

管理员页面展示脱敏号码、验证状态、provider 状态、当前销售、最近同步时间和可恢复错误；不展示完整 AccessKey Secret、Meta token、授权 code、验证码或可逆密钥。

## 分配、收回与转交状态机

账号分配动作只有以下三种：

```text
UNASSIGNED --ASSIGN--> ASSIGNED(sales A)
ASSIGNED(sales A) --RECLAIM--> UNASSIGNED
ASSIGNED(sales A) --TRANSFER--> ASSIGNED(sales B)
```

管理员必须提供非空原因。目标用户必须存在且是活跃账号（`users.status = 'active'`），不限定角色——管理员同样可以持有号码，这与迁移按 `owner_user_id` 原样继承归属的口径一致。分配事务锁定 `channel_accounts`，检查目标用户当前没有其他有效 WhatsApp 账号，再原子更新 owner、账号版本和审计。

转交给新销售时，同一事务为该账号所有既有会话建立不失效的 `conversation_access_grants`。原销售已有的会话访问保持不变。未来该账号收到的新会话默认分配给当时的当前销售；原销售不会自动获得转交后新创建的会话。

收回后 `owner_user_id` 为空，账号不可用于新发送，但 webhook、状态回执和同步仍按稳定 `channel_account_id` 入库。收回不把 `auth_status` 改为永久禁用，不清空加密凭据，也不删除 provider scope。

重复提交相同目标 owner 的分配请求幂等返回当前结果；基于旧版本的并发分配返回 `WHATSAPP_ASSIGNMENT_CONFLICT`。

## 消息和会话规则

### 历史消息

- 已持久化消息、会话、联系人身份和附件均不改挂、不复制、不删除。
- 原销售可查看其在分配期内已有访问权的会话和完整消息历史，但账号不再出现在发送账号列表，所有发送命令被拒绝。
- 新销售获得账号转交前全部既有会话访问权，并能查看原销售发送的消息；界面按 `created_by_user_id` 显示真实发送人。
- 管理员按现有管理员会话权限查看完整历史。
- 联系人列表和时间线继续通过会话分配或 `conversation_access_grants` 授权，不能重新加入 `channel_accounts.owner_user_id = 当前用户` 的单一过滤条件。

### 新消息

每次同步发送在调用 CAMS 前锁定账号并校验：账号当前 owner 是操作者、账号状态可发送、provider scope 可用。外部调用完成前管理员分配事务等待该锁，从而得到清晰顺序：先获得账号锁的一方决定该消息属于收回前还是收回后。

异步发送任务保存创建时的 `channel_account_id`、`created_by_user_id` 和账号 `version`。worker 每次真正调用 CAMS 前重新校验当前 owner 和版本：

- 已经提交 CAMS 并取得 provider message id 的任务继续接收和写入状态回执；
- 尚未提交 CAMS 且 owner 或版本已变化的任务终止为 `ACCOUNT_REASSIGNED`，不得换成新 owner 继续发送；
- 已部分提交的批量任务只停止尚未提交的收件人，已提交部分继续正常回写状态。

Webhook 和 provider 状态回执只按稳定 provider message id、账号和会话投影，不要求消息创建者仍是当前 owner。

## 服务端接口

管理员接口统一位于 `/api/admin/whatsapp/accounts`：

- `GET /api/admin/whatsapp/accounts`：列出本地账号、CAMS 状态、脱敏号码、当前销售和版本。
- `POST /api/admin/whatsapp/accounts/sync`：从已配置 CAMS scope 执行只读同步并导入或刷新号码。
- `POST /api/admin/whatsapp/accounts/{accountId}/assign`：将未分配账号分配给销售，接受 `targetOwnerId/reason/expectedVersion`。
- `POST /api/admin/whatsapp/accounts/{accountId}/reclaim`：收回账号，接受 `reason/expectedVersion`。
- `POST /api/admin/whatsapp/accounts/{accountId}/transfer`：从当前销售转交给另一销售，接受 `targetOwnerId/reason/expectedVersion`。
- `GET /api/admin/whatsapp/accounts/{accountId}/assignment-history`：返回脱敏分配审计。

所有管理员接口必须在服务端校验 `admin` 角色。销售接口保持 owner-only：账号列表只返回当前分配账号，发送接口必须校验当前 owner；历史会话读取继续使用会话访问合同。

旧的 `/api/whatsapp/authorization/**` 和 `/api/whatsapp/api-phone-operations/**` 不再作为产品入口。前端移除对应按钮和表单；后端对已通过鉴权的请求直接返回结构化的 `WHATSAPP_SELF_SERVICE_DISABLED`，待后续明确的破坏性 API 清理任务再删除代码和 schema。

## 前端页面

渠道设置页对管理员显示“WhatsApp 账号管理”：

- 顶部提供 CAMS 同步命令及最近同步结果；
- 表格显示脱敏号码、账号名称、provider/验证状态、当前销售和最近同步时间；
- 未分配账号提供“分配”，已分配账号提供“收回”和“转交”；
- 分配/转交使用现有管理员用户目录选择目标用户（活跃账号即可，不限角色），并要求填写原因；
- 管理页额外列出“我持有的号码”，数据取自 owner 视角的账号接口，因此已停用或已收回的号码不在其中；
- 冲突时刷新账号版本和当前 owner，不进行乐观覆盖。

普通销售只看到当前分配给自己的 WhatsApp 账号摘要和同步状态，不看到 CAMS 凭据、WABA、scope、其他销售或管理员操作入口。已收回账号的历史会话仍在联系人和会话页面显示为只读，不在渠道设置页伪装成有效账号。

## 错误与观测

使用结构化错误码：

- `WHATSAPP_ADMIN_REQUIRED`
- `WHATSAPP_ACCOUNT_NOT_FOUND`
- `WHATSAPP_ACCOUNT_NOT_SENDABLE`
- `WHATSAPP_TARGET_USER_INVALID`
- `WHATSAPP_TARGET_ACCOUNT_ALREADY_EXISTS`
- `WHATSAPP_ASSIGNMENT_CONFLICT`
- `WHATSAPP_ACCOUNT_REASSIGNED`
- `WHATSAPP_CAMS_SYNC_UNAVAILABLE`
- `WHATSAPP_SELF_SERVICE_DISABLED`

CAMS 同步日志只记录动作、阶段、有限 provider 错误码、RequestId、耗时和账号 ID；不得记录 AccessKey、Secret、完整号码、消息正文或未脱敏 provider 响应。

## 迁移与兼容边界

现有有效 WhatsApp 账号保留稳定 ID、provider scope、消息和会话。迁移根据当前 `owner_user_id` 建立初始 `ASSIGN` 审计，并为该 owner 已有账号会话补齐访问授权。无法证明 owner 的记录保持未分配，交由管理员处理，不自动猜测销售。

现有 `auth_status = disabled` 不能直接等同于“已收回”，因为旧逻辑可能清空过账号凭据。迁移不得自动恢复这类账号；恢复只发生在管理员显式同步、且 CAMS 确认该号码可发送时（见“管理员配置与账号发现”）。

不保留 Embedded Signup 的前端双路径。旧自助 attempt、候选号码和号码注册 operation 作为历史审计保留，不允许继续推进状态机或创建账号。

## 验收标准

1. 管理员可从 CAMS 同步至少一个已验证号码，并在页面分配给销售 A。
2. 销售 A 可发送消息；未分配用户不能使用该账号。
3. 管理员转交给销售 B 后，销售 A 不能发送，销售 B 可以发送并查看转交前完整历史。
4. 销售 A 仍可只读查看其已有历史会话；不能查看转交后新建且从未向其授权的会话。
5. 历史消息的 `channel_account_id` 和 `created_by_user_id` 在转交前后保持不变。
6. 收回时已提交 CAMS 的消息继续更新状态；未提交的旧 owner 异步任务终止为 `ACCOUNT_REASSIGNED`。
7. 同一号码不能重复导入，同一销售不能同时拥有两个有效 WhatsApp 账号，并发分配不会产生双 owner。
8. 普通用户无法调用任何管理员分配接口，管理员 API 不返回秘密或完整号码。
9. 页面不再调用 `IsvGetAppId`，不显示 Embedded Signup 或员工自助号码注册入口。
10. 后端专项测试、前端页面测试、生产构建和真实 CAMS 只读号码同步均通过；真实测试证据只保存脱敏状态与 RequestId。

## 非目标

- 申请 Meta Tech Provider、Solution Partner 或阿里云 CAMS ISV 资格。
- 客户或员工自助创建 WABA、绑定 Business App 共存账号或添加电话号码。
- 一个 WhatsApp 电话同时由多个销售发送。
- 自动迁移、合并或重写历史联系人和消息 owner。
- CRM 删除或注销 CAMS/Meta 侧资源。
