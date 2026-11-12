# 助手授权读取边界验收记录

日期：2026-09-28
状态：联系人授权渠道投影专项验收通过；本轮不自动提交。文档仍记录助手历史上下文泄露等非本任务阻断边界。

## 范围与证据

- 当前用户 ID 经助手工具传入领域 owner；联系人候选和简报先重新授权联系人，再读取 owner-scoped 渠道身份投影。`identityScope` 只用于按当前用户解析账号标签，不进入模型；普通联系人列表/详情 API 未改身份可见行为。
- 审计新写入行只含用户原话的 `chars:<n>;sha256:<64位摘要>`、参数顶层键名及值类型、规范化参数摘要和操作元数据；原话、正文、原始地址、嵌套参数值不写入审计行。SHA-256 对低熵值不等同匿名化。
- 已覆盖的有效格式引用不存在或无权访问路径返回 `FORBIDDEN_OR_NOT_FOUND` 码及中性文案；格式不正确仍返回 `INVALID_ARGUMENT`。`message.read` 的单条显式正文保留，但不投影 `from/to`。联系人写入的字段校验不再误映射为权限错误。
- 架构测试以 JUnit Jupiter 测试运行 ArchUnit，禁止 `service.assistant.mcp..` 直接依赖 `mapper..`。

## 本轮命令结果

| 命令 | 结果 |
|---|---|
| `mvn -q -Dtest=ContactServiceAuthorizationTest,ContactBriefProviderTest,ContactAssistantToolsTest test` | Surefire 3 类、46 例；失败 0、错误 0、跳过 0。覆盖 owner-scoped 渠道身份、账号标签解析、候选/简报 `channels` 白名单和内部字段排除。 |
| `mvn -q -DskipTests compile` | 退出码 0。 |
| `mvn -q -Dtest=ContactCandidateProviderTest,ContactServiceAuthorizationTest,ContactServicePhoneBindingTest,ContactBriefProviderTest,ContactAssistantToolsTest test` | Surefire 5 类、50 例；失败 0、错误 0、跳过 0。覆盖候选 provider 使用当前用户、owner-scoped 渠道身份投影、账号标签解析、简报/MCP `channels` 白名单和内部字段排除。 |
| `mvn -q -Dtest=AppIntegrationTest test` | 沙箱外连接 Docker/PostgreSQL；10 例通过，失败 0、错误 0、跳过 0。沙箱内 Docker socket 被拒时的退出码 0 只代表跳过，不作为验收证据。 |
| `mvn -q -Dtest=AssistantLiveConversationTest test` | 2 例均因缺少 `AI_API_KEY` 跳过；未验证真实模型轮次的新审计断言。 |
| `git diff --check` | 退出码 0；仅检查已跟踪文件，未跟踪文件另行审查。 |

上述 241 例统计早于联系人写入字段校验/授权异常的最后一次修正；修正后 `mvn -q -Dtest=ContactWriteAssistantToolsTest test` 已退出码 0。最终合并回归需在阻断合同确定并修复后重跑。

## 剩余边界

- **阻断：前端待确认卡片的 summary 含收件地址和正文，却同时复制到会话 `message` 持久化并在下一轮作为模型历史；发送成功回执的 message/data 亦含原始地址。** 当前代码与设计 §4.5 的“原始地址不进通用模型上下文”冲突。推荐保留前端专用可核对回执，给模型历史单独生成无地址/正文的结构化投影；修复前须确认产品合同，随后补跨轮测试。
- **隐私缺口：** `ToolInputValidator` 日期/时间校验把原始输入拼进错误消息，`ToolRegistry` 的 DEBUG 日志原样记录该消息；`TodoAssistantTools` 也会把日期或任意 `IllegalArgumentException.message` 回显。包含地址、手机号或凭证的误填值可进入日志/结果。须以安全固定文案替代原值并补错误与日志测试。
- 历史 `assistant_action_audit` 行可能保留原话和参数明文；本轮不修改已安装迁移、不静默擦除历史审计。上线前需制定授权的数据保留/清理操作与回滚策略。
- 当前测试未覆盖真实模型调用，也未对联系人身份 owner SQL 跑完整的团队、过期授权、管理员和合并/软删除数据库矩阵。部署后需在授权的隔离数据环境验收。
- 本轮代码跨多个原本未跟踪的既有助手工具文件与测试文件；这些文件包含用户既有 WIP。未将整文件暂存或提交，以免把无关工作吸入本任务。普通联系人 API 的历史“联系人可见后无范围读取全部身份”路径保持原状，须作为独立权限决策处理。
