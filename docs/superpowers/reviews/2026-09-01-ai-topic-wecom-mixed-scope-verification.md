# AI Topic 企业微信混合归属与静默重构验收记录

日期：2026-09-01

## 已通过

- 后端专项：`mvn -q -Dtest=AiTopicControllerMixedScopeTest,AiTopicStoreApprovalServiceTest test`，包含群 Topic 入库申请的批准与拒绝队列回归。
- 前端专项：`npm run test:ui -- --run src/components/AiTopicTimeline.test.tsx src/pages/TopicRepositoryPage.test.tsx`，5 个测试通过。
- 前端构建：`npm run build`，Vite 生产构建成功。
- 后端编译打包：`mvn -q -DskipTests package`，生成 `backend/target/message-center.jar`。
- Jar SHA-256：`2f32b5a99172f6e3354f27eac0ed466bfb97ee277e3b04b0b43e8778a7a16dc1`。
- 前端压缩包：`frontend/frontend-dist-20260901-ai-topic-wecom-mixed-r1.zip`，SHA-256 为 `d20cee23f8733b4ce41183995447595add912b499cd62f770a8a5bbeb721dedd`。

## 环境门禁

- `mvn -q test` 共 885 个测试，0 断言失败；23 个集成测试因当前环境无法访问 Docker socket/外部网络而报错，需在部署服务器补跑。
- 本地直接启动 Jar 已进入 Spring Boot/Tomcat 初始化，但因未提供 `MINIO_ENDPOINT`（`endpoint must not be null`）退出；配置完整的部署环境需重新启动确认 Flyway 与数据库连接。

## 交付边界

Topic 输入、群 owner 引用、STORED 仓库、管理员审批和 SSE 终态刷新已接线。数据库迁移 V32/V33 的真实 PostgreSQL 空库及现有库演练、带完整 `.env` 的服务器启动和 API 实机验证不在本机门禁内。
