# 内部设计真源索引

本目录保存消息中心当前有效的内部设计、实施计划和验收记录。事实冲突时，先以当前源码、测试、运行日志和实机证据为准，再按本索引列出的当前设计处理。

## 当前设计

- [WhatsApp 共享模板与变更审批设计](specs/2026-09-05-whatsapp-shared-template-approval-design.md)：WhatsApp 模板权限当前最高层真源；定义系统级共享模板、新模板直接官方申请、普通用户变更审批、管理员直接执行、共享身份迁移和凭证隔离。
- [用户私有渠道通讯录设计](specs/2026-09-04-user-channel-address-books-design.md)：定义 WhatsApp、邮件和电话按用户隔离的渠道账号、联系人、身份、标签、通话记录、通讯录页面、历史迁移和权限边界；企业微信沿用现有实现。
- [企业微信代开发成员头像 OAuth2 授权设计](specs/2026-09-04-wecom-avatar-oauth-design.md)：定义 Web 登录与敏感头像授权分离、跨设备二维码授权、一次性 state、成员绑定校验和终态刷新。
- [账户生命周期与用户资料设计](specs/2026-09-03-account-lifecycle-design.md)：定义公开注册、当前账户资料、密码管理、MinIO 用户头像、企业微信头像优先级和管理员角色管理。
- [AI Topic 手动整理与联系人合并拆分设计](specs/2026-09-02-ai-topic-manual-review-contact-merge-split-design.md)：当前 AI Topic 人工整理与联系人结构变化的最高层真源；定义 `REVIEW_PENDING`、按联系方式/时间选源、AI 预览、融合生成新 Topic，以及联系人合并/拆分后的来源迁移。
- [AI Topic 企业微信混合归属与静默重构设计](specs/2026-09-01-ai-topic-wecom-mixed-scope-design.md)：AI Topic 自动生成与企业微信混合归属基础真源；定义个人/群 Topic 归属、企业微信摘要输入、6 分钟静默重构、群 Topic 入库审批、混合时间轴和仓库投影。
- [AI Topic 生命周期、异步任务与弃用仓库设计](specs/2026-08-28-ai-topic-lifecycle-and-repository-design.md)：当前 AI Topic 真源；定义非企业微信渠道的增量归类、异步命令、右侧时间轴、弃用隔离与跨联系人弃用仓库。
- [企业微信会话身份与资料设计](specs/2026-08-24-wecom-conversation-identity-design.md)：定义代开发安装实例、ChatData 源会话、参与者、成员资料、企业名称和访问边界。
- [企业微信统一会话工作区设计](specs/2026-08-25-wecom-unified-conversation-workspace-design.md)：定义联系人直聊聚合、左侧独立群聊、群发送者与参与者、单 OpenDataFrame 串行更新和导航行为。涉及 UI、路由、会话列表或 frame 生命周期时，以该文档为当前真源。
- [企业微信消息级官方摘要设计](specs/2026-08-31-wecom-message-summary-design.md)：定义每条企业微信消息异步调用 `conversation_daily_summary`、原始响应审计、失败分层和消息保留保护。

## 当前实施

- [WhatsApp 共享模板与变更审批实施计划](plans/2026-09-05-whatsapp-shared-template-approval.md)：把共享 provider scope、账号副本归并、新模板直接官方申请、普通用户变更审批、管理员直接执行、发送解析切换和发布验收拆为九个可独立提交的 Task。
- [企业微信代开发成员头像 OAuth2 授权实施计划](plans/2026-09-04-wecom-avatar-oauth.md)：落实独立敏感授权二维码、一次性回调、绑定成员校验、终态轮询和 Web 登录 scope 清理。
- [账户生命周期与用户资料实施计划](plans/2026-09-03-account-lifecycle.md)：落实公开注册、当前用户资料与头像、凭原密码改密、会话轮换和管理员用户管理。
- [AI Topic 手动整理与联系人合并拆分实施计划](plans/2026-09-02-ai-topic-manual-review-contact-merge-split.md)：落实待确定区、人工选源预览、Topic 融合，以及联系人合并/拆分的 Topic 来源迁移。
- [AI Topic 企业微信混合归属与静默重构实施计划](plans/2026-09-01-ai-topic-wecom-mixed-scope.md)：落实个人/群 owner、企业微信单条摘要混合输入、6 分钟静默重构、群 Topic 入库审批和最终快照刷新。
- [AI Topic 联系方式合并重关联实施计划](plans/2026-09-01-ai-topic-contact-merge-reconciliation.md)：联系方式或联系人合并后，重新关联全部历史来源与现有 Topic，并在 AI 成功后原位迁移来源。

- [AI Topic 生命周期与弃用仓库实施计划](plans/2026-08-28-ai-topic-lifecycle-and-repository.md)：落实异步 Topic 操作、未归类来源增量聚合、弃用仓库和最终快照刷新。
- [企业微信统一会话工作区实施计划](plans/2026-08-25-wecom-unified-conversation-workspace.md)：Task 1-7 已完成；Task 8 的 Docker/服务器实机门禁需在部署环境执行。
- [联系人备注与标签恢复实施计划](plans/2026-08-31-contact-remark-and-tags.md)：后端标签投影、标签替换接口、前端备注展示与标签编辑已完成；本地数据库实机 API 验证需在运行环境执行。
- [企业微信消息级官方摘要实施计划](plans/2026-08-31-wecom-message-summary.md)：落实消息入库同事务入队、单条提交/轮询 worker、历史补偿、诊断查询和发布验收。

## 当前验收

- [企业微信代开发成员头像 OAuth2 授权验收记录](reviews/2026-09-04-wecom-avatar-oauth-verification.md)：记录独立敏感授权 API、前端二维码入口、自动化门禁、浏览器验收、制品校验和服务器扫码停止条件。
- [账户生命周期与用户资料验收记录](reviews/2026-09-03-account-lifecycle-verification.md)：记录账户专项测试、前端全量测试、桌面/移动浏览器验收、Jar/ZIP 制品及后端全量测试环境门禁。
- [AI Topic 手动整理与联系人合并拆分验收记录](reviews/2026-09-02-ai-topic-manual-review-contact-merge-split-verification.md)：记录 Topic/联系人专项测试、前端全量测试、浏览器桌面验收、Jar/ZIP 制品与部署边界。
- [AI Topic 企业微信混合归属与静默重构验收记录](reviews/2026-09-01-ai-topic-wecom-mixed-scope-verification.md)：记录后端/前端专项测试、构建、Jar 与前端压缩包产物，以及 Docker/本地运行环境门禁。

- [企业微信消息级官方摘要验收记录](reviews/2026-08-31-wecom-message-summary-verification.md)：专项门禁已通过；全量回归受既有 Topic WIP 和本机 Docker 不可用影响。
- [用户私有渠道通讯录验收记录](reviews/2026-09-04-user-channel-address-books-verification.md)：记录 owner 隔离专项测试、前端全量测试、构建、ZIP/Jar 制品和部署验收边界。

## 已被吸收的设计

- `specs/2026-08-28-ai-topic-lifecycle-and-repository-design.md` 已被 2026-09-01 的 AI Topic 企业微信混合归属与静默重构设计吸收；其中仅支持 ChatApp/邮件/电话、`DISCARDED` 弃用语义和联系人独占 Topic 归属不再有效。
- `specs/2026-08-27-ai-topic-timeline-design.md` 已被 2026-08-28 的 AI Topic 生命周期、异步任务与弃用仓库设计吸收；其中前端轮询、同步编辑/合并命令和仅 `READY/ARCHIVED` 的状态模型不再有效。
- `specs/2026-08-22-wecom-conversation-full-height-design.md` 的全高布局要求已被统一会话工作区设计吸收。
- `specs/2026-08-25-wecom-group-direction-frame-design.md` 的群聊方向和单 frame 要求已被统一会话工作区设计吸收；其中“群聊进入联系人顶部选择器”和“允许并发 setData 的 latest-wins 更新”不再有效。
- `plans/2026-08-25-wecom-single-frame-history-cache.md` 仅保留为历史实施记录；其缓存合同不得覆盖统一会话工作区设计中的 frame 串行化和实机验收要求。
