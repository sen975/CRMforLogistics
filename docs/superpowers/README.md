# 内部设计真源索引

本目录保存消息中心当前有效的内部设计、实施计划和验收记录。事实冲突时，先以当前源码、测试、运行日志和实机证据为准，再按本索引列出的当前设计处理。

## 当前设计

- [AI Topic 企业微信混合归属与静默重构设计](specs/2026-09-01-ai-topic-wecom-mixed-scope-design.md)：当前 AI Topic 最高层真源；定义个人/群 Topic 归属、企业微信摘要输入、6 分钟静默重构、群 Topic 入库审批、混合时间轴和仓库投影。
- [AI Topic 生命周期、异步任务与弃用仓库设计](specs/2026-08-28-ai-topic-lifecycle-and-repository-design.md)：当前 AI Topic 真源；定义非企业微信渠道的增量归类、异步命令、右侧时间轴、弃用隔离与跨联系人弃用仓库。
- [企业微信会话身份与资料设计](specs/2026-08-24-wecom-conversation-identity-design.md)：定义代开发安装实例、ChatData 源会话、参与者、成员资料、企业名称和访问边界。
- [企业微信统一会话工作区设计](specs/2026-08-25-wecom-unified-conversation-workspace-design.md)：定义联系人直聊聚合、左侧独立群聊、群发送者与参与者、单 OpenDataFrame 串行更新和导航行为。涉及 UI、路由、会话列表或 frame 生命周期时，以该文档为当前真源。
- [企业微信消息级官方摘要设计](specs/2026-08-31-wecom-message-summary-design.md)：定义每条企业微信消息异步调用 `conversation_daily_summary`、原始响应审计、失败分层和消息保留保护。

## 当前实施

- [AI Topic 企业微信混合归属与静默重构实施计划](plans/2026-09-01-ai-topic-wecom-mixed-scope.md)：落实个人/群 owner、企业微信单条摘要混合输入、6 分钟静默重构、群 Topic 入库审批和最终快照刷新。
- [AI Topic 联系方式合并重关联实施计划](plans/2026-09-01-ai-topic-contact-merge-reconciliation.md)：联系方式或联系人合并后，重新关联全部历史来源与现有 Topic，并在 AI 成功后原位迁移来源。

- [AI Topic 生命周期与弃用仓库实施计划](plans/2026-08-28-ai-topic-lifecycle-and-repository.md)：落实异步 Topic 操作、未归类来源增量聚合、弃用仓库和最终快照刷新。
- [企业微信统一会话工作区实施计划](plans/2026-08-25-wecom-unified-conversation-workspace.md)：Task 1-7 已完成；Task 8 的 Docker/服务器实机门禁需在部署环境执行。
- [联系人备注与标签恢复实施计划](plans/2026-08-31-contact-remark-and-tags.md)：后端标签投影、标签替换接口、前端备注展示与标签编辑已完成；本地数据库实机 API 验证需在运行环境执行。
- [企业微信消息级官方摘要实施计划](plans/2026-08-31-wecom-message-summary.md)：落实消息入库同事务入队、单条提交/轮询 worker、历史补偿、诊断查询和发布验收。

## 当前验收

- [AI Topic 企业微信混合归属与静默重构验收记录](reviews/2026-09-01-ai-topic-wecom-mixed-scope-verification.md)：记录后端/前端专项测试、构建、Jar 与前端压缩包产物，以及 Docker/本地运行环境门禁。

- [企业微信消息级官方摘要验收记录](reviews/2026-08-31-wecom-message-summary-verification.md)：专项门禁已通过；全量回归受既有 Topic WIP 和本机 Docker 不可用影响。

## 已被吸收的设计

- `specs/2026-08-28-ai-topic-lifecycle-and-repository-design.md` 已被 2026-09-01 的 AI Topic 企业微信混合归属与静默重构设计吸收；其中仅支持 ChatApp/邮件/电话、`DISCARDED` 弃用语义和联系人独占 Topic 归属不再有效。
- `specs/2026-08-27-ai-topic-timeline-design.md` 已被 2026-08-28 的 AI Topic 生命周期、异步任务与弃用仓库设计吸收；其中前端轮询、同步编辑/合并命令和仅 `READY/ARCHIVED` 的状态模型不再有效。
- `specs/2026-08-22-wecom-conversation-full-height-design.md` 的全高布局要求已被统一会话工作区设计吸收。
- `specs/2026-08-25-wecom-group-direction-frame-design.md` 的群聊方向和单 frame 要求已被统一会话工作区设计吸收；其中“群聊进入联系人顶部选择器”和“允许并发 setData 的 latest-wins 更新”不再有效。
- `plans/2026-08-25-wecom-single-frame-history-cache.md` 仅保留为历史实施记录；其缓存合同不得覆盖统一会话工作区设计中的 frame 串行化和实机验收要求。
