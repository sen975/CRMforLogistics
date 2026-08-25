# 企业微信群会话、方向与切换稳定性验收记录

## 已验证

- 后端专项：`WeComChatDataSyncRuntimeConditionTest,WeComStartupGateEntryTest,ThreadServiceWeComTest,WeComDirectionResolverTest,WeComChatDataStoreTest,WeComMessageProjectorTest,MessageResponseSourceConversationContractTest` 通过。
- 前端全量：29 个 source tests、38 个 UI test files、194 个 UI tests 通过。
- 单 frame 切换专项：联系人 A→B 时 `createOpenDataFrame` 只调用一次，切换前先写入空 `msgList`，切换期间不调用 `dispose`；旧异步更新晚到时重放最新数据。
- 全高回归专项：官方 SDK 返回的 `frame.el` 显式使用 `display: block; width: 100%; height: 100%; min-width: 0; min-height: 0; border: 0`，避免浏览器按 iframe 默认尺寸渲染小窗。该修复不改变单 frame 清空重填策略。
- 前端生产构建：TypeScript 和 Vite 构建通过，生成 61 个 assets。
- 后端生产打包：`mvn -q -DskipTests package` 通过；Jar 压缩结构校验通过。

## 全量后端限制

`mvn test` 实际执行 767 个测试。当前受控环境不允许访问 Docker socket 和本地测试 HTTP socket，导致 22 个 Testcontainers/socket 测试报 `Operation not permitted`；本轮曾暴露的两个 Spring 构造器失败已修复，并由条件测试专项复验通过。服务器仍需运行 `scripts/wecom-server-acceptance.sh` 完成真实企业微信权限和数据验收。

## 发布物

- Jar：`demo/message-center-spring/backend/target/message-center.jar`
- Jar SHA-256：`f405bfc03493aae4acb285f977acf848cfe0e552531d8ed22fef78736aab1998`
- Frontend：`demo/message-center-spring/frontend/dist/`
- 前端入口：`/assets/index-XobL0Yn5.js`
- 前端部署包：`demo/message-center-spring/frontend-dist-20260825-wecom-full-timeline-single-frame-r2.zip`
- 前端部署包 SHA-256：`d0ec42c9f87cec09591d56cebd5b9a50011bc85a7ec100414af4b2d91d864b89`
