# 企业微信群会话、方向与切换稳定性设计

## 目标

修复三个相互关联但职责不同的问题：群聊按 `chatId` 独立展示、内部员工消息按当前登录成员正确计算方向、企业微信会话组件在联系人连续切换时始终渲染最新联系人。

## 产品语义

- 每个企业微信群 `chatId` 是独立源会话；群消息不得归入某个外部联系人的直聊窗口。
- 企业微信直聊继续按联系人展示。
- 消息方向以当前登录企业微信成员为视角：当前成员发送为 `outbound`，其他参与者发送且当前成员在会话参与者中为 `inbound`。无法确认当前成员参与关系时不伪造方向，记录结构化不可判定原因。
- A 到 B 的切换与 A 到其他渠道再回 B 使用同一 frame 生命周期；旧请求不得覆盖新联系人，卡住的 SDK 更新必须可恢复。

## 数据与接口合同

`MessageResponse` 增加可选字段：

- `sourceConversationId`：CRM 源会话 UUID；普通非 WeCom 消息为空。
- `conversationType`：`DIRECT` 或 `GROUP`；非 WeCom 为空。
- `conversationDisplayName`：群名称或已解析的源会话名称。

`ThreadService` 在查询 WeCom 源会话时保留每个 `sourceConversationId`，不得仅按联系人合并。群源会话仍保持 `contact_identity_id = null`，访问控制通过参与者和现有会话授权完成。

方向计算属于后端共享合同：读取当前 CRM 用户绑定的 `wecomUserId`，结合源消息 sender 和 participants 计算；前端只消费 `direction`。

## 前端行为

企业微信面板从当前线程消息中按 `sourceConversationId` 建立会话列表。直聊只有一个入口；群聊显示群名称并可切换。选中的会话只向 viewer 准备该源会话的消息引用。

`WeComConversationFrame` 保持单 frame 的“清空后重填”策略，不因联系人切换创建或销毁 frame。更新采用最新请求优先：旧 generation 的准备结果和错误均被丢弃，旧 `setData` 卡住时新 generation 的清空和重填不等待旧 Promise；旧调用完成后重放最新数据，确保最终内容属于当前联系人。

## 错误与观测

- 群会话缺少源 ID、消息方向不可判定、SDK 更新超时均返回结构化错误状态，不显示空白占位符。
- 不记录消息正文、secretKey、viewer token；审计记录仅包含阶段、源会话 ID 摘要和错误码。

## 验收

- 后端测试覆盖：同一联系人下两个群生成两个 conversation；员工 A/B 相互发消息时相对当前 viewer 分别为 inbound/outbound；非参与者不可读。
- 前端测试覆盖：群会话选择、A→B 乱序响应、A→B pending update 超时后重建，以及 A→其他→B 对照路径。
- `mvn -q -Dtest=... test`、`npm test`、`npm run build` 均通过后才生成 Jar。
