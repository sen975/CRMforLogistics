# 内部设计真源索引

本目录保存消息中心当前有效的内部设计、实施计划和验收记录。事实冲突时，先以当前源码、测试、运行日志和实机证据为准，再按本索引列出的当前设计处理。

## 当前设计

- [企业微信会话身份与资料设计](specs/2026-08-24-wecom-conversation-identity-design.md)：定义代开发安装实例、ChatData 源会话、参与者、成员资料、企业名称和访问边界。
- [企业微信统一会话工作区设计](specs/2026-08-25-wecom-unified-conversation-workspace-design.md)：定义联系人直聊聚合、左侧独立群聊、群发送者与参与者、单 OpenDataFrame 串行更新和导航行为。涉及 UI、路由、会话列表或 frame 生命周期时，以该文档为当前真源。

## 当前实施

- [企业微信统一会话工作区实施计划](plans/2026-08-25-wecom-unified-conversation-workspace.md)：Task 1-7 已完成；Task 8 的 Docker/服务器实机门禁需在部署环境执行。

## 已被吸收的设计

- `specs/2026-08-22-wecom-conversation-full-height-design.md` 的全高布局要求已被统一会话工作区设计吸收。
- `specs/2026-08-25-wecom-group-direction-frame-design.md` 的群聊方向和单 frame 要求已被统一会话工作区设计吸收；其中“群聊进入联系人顶部选择器”和“允许并发 setData 的 latest-wins 更新”不再有效。
- `plans/2026-08-25-wecom-single-frame-history-cache.md` 仅保留为历史实施记录；其缓存合同不得覆盖统一会话工作区设计中的 frame 串行化和实机验收要求。
