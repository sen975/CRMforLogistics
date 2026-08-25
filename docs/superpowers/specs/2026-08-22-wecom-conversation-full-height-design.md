# 企业微信会话容器铺满时间轴设计

## 目标

当联系人切换到企业微信会话页时，右侧时间轴区域由企业微信会话页完整占用。会话 SDK 宿主必须填满可用宽度和高度，不能因为外层 padding、内层滚动容器或按消息条数计算的固定高度而缩成中间小块。

## 保留行为

- 顶部“企业微信会话”标题和刷新按钮保留。
- 底部“在企业微信中打开”按钮保留。
- 非企业微信联系方式继续使用现有混合时间线。
- 后端 API、会话数据、SDK 初始化和 Jar 不变。

## 实现边界

`WeComConversationPanel` 负责整页 flex 布局；中间内容区使用 `flex: 1 1 auto; min-height: 0; min-width: 0`，不再添加会造成视觉内缩的 padding。`WeComTimelineSegment` 在 `standalone` 模式下让外层、frame wrapper 和 SDK host 逐级使用 `width: 100%; height: 100%`，SDK host 不自行制造额外滚动区域。混合模式保持原有消息条高度和底部标签。

官方 OpenDataFrame 创建的 iframe 默认没有宽高，浏览器会按默认 iframe 尺寸渲染；挂载回调必须把官方实例的 `el` 设置为 `display: block; width: 100%; height: 100%`。官方实例销毁接口是 `dispose()`，不能调用不存在的 `destroy()`。

## 失败与空态

加载中、加载失败、无消息状态仍覆盖中间内容区；失败重试按钮和 SDK 错误上报逻辑不变。不会用固定高度或假数据掩盖布局问题。

## 验收

- `WeComConversationPanel` 测试断言中间内容区具有填充布局且保留刷新/唤起按钮。
- `WeComTimelineSegment` 测试断言 standalone 容器和 SDK host 使用全尺寸布局，mixed 模式行为不变。
- 前端源码测试、UI 测试和 `npm run build` 全部通过。
