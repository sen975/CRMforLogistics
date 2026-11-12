# Topic 时间轴与仓库归属展示实施计划

> **For agentic workers:** 本计划在当前会话内按任务逐项执行。

**目标：** 在 Topic 时间轴中突出事件时间，并在 Topic 仓库保留可定位的原联系人归属信息。

**架构：** 复用后端已有 `firstOccurredAt`、`lastOccurredAt`、`contactId` 字段；前端只负责结构化展示。时间轴使用固定的事件时间栏，Topic 折叠时仍在标题行显示最近事件时间。仓库显示联系人名称（若接口提供）和稳定 contactId，恢复仍调用现有接口。

**技术栈：** React、TypeScript、Ant Design、Vitest、Spring Boot record DTO。

## 全局约束

- 不改变 Topic 聚合、弃用、恢复和异步任务语义。
- 不展示纯 WeCom Topic；混合 Topic 保留非 WeCom 总结，并隐藏 WeCom 来源入口。
- 不删除来源数据，仅保持现有折叠入口。
- 所有前端文案使用中文，时间使用 `zh-CN` 本地化。

### 任务 1：补充 Topic 归属字段合同
- 修改后端 `TopicProjection` 增加可选 `contactName`。
- 从联系人实体读取显示名；名称为空时前端回退为 contactId。
- 同步前端 `TopicProjection` 类型。

### 任务 2：时间轴视觉层级
- Topic 折叠标题显示最近事件时间。
- Topic 内容显示带“事件时间”标签的首个/最近事件。
- 保持整个时间轴可折叠。

### 任务 3：Topic 仓库归属展示
- 每项显示“归属联系人”、名称、contactId。
- 恢复按钮继续使用 contactId。
- 增加渲染回归测试。

### 任务 4：专项验证
- 运行前端 UI 测试与 TypeScript/Vite 构建。
- 检查 diff 与生成物状态。
