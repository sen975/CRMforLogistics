# 企业微信联系人级消息窗口设计

**日期：** 2026-08-10
**状态：** 已确认，等待实施
**替代：** 本文废止同文件早期版本中的“每条消息一个 OpenDataFrame、`display-type="text"` 单行卡片”路线。

## 1. 问题与目标

当前时间线为每条企业微信消息创建一个 OpenDataFrame，并使用企业微信官方文档未声明支持的 `display-type="text"`。真实授权环境表明外层 Frame 可以完成挂载，但内部 `ww-open-message` 仍为空白；点击后只是展开同一个空白组件，没有重新加载正文。

本轮目标是让打开联系人时自动加载最近 15 条企业微信消息，并让点击只承担官方详情预览。页面不得再显示空白消息框或由 `.msg.active` 产生的大面积蓝色轮廓。

本轮不改变会话同步、消息方向、时间线排序、viewer session、密钥来源、专区程序协议、Email、ChatApp、电话记录或 Spring 版前端。

## 2. 平台约束

- `ww-open-message` 正文由企业微信 OpenDataFrame 渲染，父页面不能读取、截断或重新排版正文。
- 使用企业微信官方示例声明的 `message-id`、`secret-key` 和 `open-type="viewMessage"`，不再使用未证实的 `display-type`。
- OpenDataFrame 模板样式只使用官方允许的 class selector，不直接通过 `ww-open-message { ... }` 控制组件内部样式。
- `handleModal` 只处理需要预览的图片、视频、聊天记录等详情；它不是消息正文重新加载事件。
- `modalUrl` 只能存在于当前页面内存和详情 iframe 的 `src`，不得写入日志、审计、后端请求、DOM data 属性或持久化缓存。

## 3. 唯一 Owner 与结构

企业微信时间线渲染 owner 保持在 `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java` 的前端模块中。

每个联系人活动窗口最多拥有一个 OpenDataFrame。该 Frame 的模板一次接收最近 15 条消息引用，并在同一模板中循环创建官方 `ww-open-message` 列表。父页面继续负责联系人窗口缓存、同步结果、登录生命周期和详情 iframe；OpenDataFrame 只负责官方消息内容。

禁止恢复以下并行路线：

- 每条消息独立创建 OpenDataFrame；
- 使用 `display-type="text"` 伪造单行文本；
- 通过裁剪跨域 iframe 假装获得自适应气泡；
- 点击空白宿主时仅切换 CSS 展开状态。

## 4. 加载流程

1. 用户打开联系人后，时间线先正常显示 Email、ChatApp 和其他已就绪消息。
2. 前端从当前联系人最近窗口中选择最多 15 条企业微信消息，保持原时间顺序和方向信息。
3. 前端为该联系人创建一个隐藏的 OpenDataFrame，并把 15 条 `{ msgid, secretKey }` 引用作为列表传入。
4. `handleMounted` 成功后，才把企业微信消息列表提交到可见时间线；挂载前不显示空白占位框。
5. 后台刷新若消息引用集合没有变化，复用现有 Frame，不重新创建。
6. 引用集合发生变化、单条重试、登录恢复或组件失效时，销毁旧实例并基于最新 15 条引用创建新实例。
7. 联系人切换时使用现有联系人窗口缓存；缓存淘汰、登录失效、页面真正卸载时销毁对应 Frame。

`handleMounted` 只证明 Frame 已挂载，不能证明每条内部组件都成功。模板的 `binderror` 必须带回稳定消息 ID，使父页面能记录失败条目并提供单条重试入口。

## 5. 展示与交互

- 企业微信列表外层不使用 `.msg.active` 的统一蓝色 outline。
- 每条企业微信消息保留与时间线方向一致的左右排列，但父页面不强制官方正文高度、字号或字符截断。
- 官方组件未完成挂载前整组保持隐藏，不留下空框。
- 点击已加载的 `ww-open-message` 时，由官方组件触发 `handleModal`。
- 合法 HTTPS `modalUrl` 在当前消息附近原位展开；iframe 完成 load 前保持隐藏，成功后再显示。
- 详情创建或加载失败时删除 iframe，恢复已加载的官方消息条目，不留下大框。
- 同一联系人最多同时保留 15 个详情 iframe；淘汰规则沿用“优先最早且离开视口，否则最早”的确定性策略。

## 6. 错误与重试

- OpenDataFrame 创建或超时：企业微信消息区显示一个紧凑的联系人级失败状态，不创建 15 个错误框。
- 单条 `ww-open-message` 报错：该条位置显示小型失败标识，其他成功条目保持可用。
- 单条重试：销毁并重建联系人级 Frame，因为官方 Frame 内部条目不能由父页面独立重载；重建期间继续显示旧的已加载 Frame，成功后原子替换。
- 登录失效：销毁所有联系人 Frame 和详情 iframe，进入现有重新登录流程。
- 错误日志只记录稳定错误类型、联系人 ID 和消息 ID，不记录 `secretKey` 或 `modalUrl`。

## 7. 资源边界

- 每个联系人一个 OpenDataFrame。
- 每个 Frame 最多 15 条消息。
- 联系人 Frame 缓存继续限制为 3 个联系人，因此稳态最多 3 个 OpenDataFrame、45 条官方消息组件。
- 同一联系人最多 15 个展开详情 iframe。
- 创建队列、超时、取消、缓存淘汰和页面卸载必须有确定性清理。
- 后台自动刷新不得在引用集合未变化时重复创建 Frame。

## 8. 测试与验收

自动化测试必须先失败再实现，并覆盖：

- HTML 不包含 `display-type="text"`；
- 模板不包含裸 `ww-open-message { ... }` 样式选择器；
- 一个联系人窗口只创建一个 OpenDataFrame；
- 同一个 Frame 接收最多 15 条按时间排序的消息引用；
- 未 `handleMounted` 时不显示空白企业微信消息区；
- 引用集合未变化时刷新复用现有实例；
- 引用变化或重试时先创建新实例，成功后再替换旧实例；
- 单条错误可关联消息 ID，且不会遮蔽其他消息；
- 企业微信消息不应用 `.msg.active` 大轮廓；
- 详情 iframe 加载成功前隐藏，失败后删除；
- 联系人缓存淘汰、登录失效和页面卸载会销毁 Frame 与详情 iframe。

针对性验收至少运行：

```bash
cd demo/message-center-demo
mvn -q -Dtest=UnifiedMessageStoreTest test
mvn -q test
node contracts/openapi/message-center-v1.test.mjs
mvn -q -DskipTests package
```

真实企业微信授权环境必须补充用户侧证据：打开含企业微信消息的联系人后，无需点击即可看到最近消息；点击消息能打开详情；刷新、切换联系人和自动轮询不会产生空白框、大蓝框或重复白屏。

## 9. 停止条件

若官方 OpenDataFrame 在单个模板中渲染消息列表仍为空白，或 `binderror` 无法关联具体消息 ID，应停止继续调整 CSS。下一步只允许收集真实 SDK 错误、模板入参和官方最小示例对照证据，不得再通过未文档化属性、自动模拟点击或跨域读取绕过平台限制。
