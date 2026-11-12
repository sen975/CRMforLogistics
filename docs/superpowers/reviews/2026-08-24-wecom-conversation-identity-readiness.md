# 企业微信会话身份实现就绪审查

日期：2026-08-24

## 已完成

- 代开发安装实例 token 统一由 `ResolvedInstallation -> WeComAccessTokenService -> /cgi-bin/gettoken` 产生。
- ChatData 已规范化成员单聊、外部联系人单聊和群会话，群会话不伪造联系人。
- 成员/外部联系人资料投影支持 `READY|PARTIAL|DEGRADED`，目录同步按部门发现和成员详情刷新。
- viewer 的无安装实例 ticket 入口已移除；前端会话容器使用完整时间轴尺寸。
- 回补编排有独立游标、五天窗口、确认、限页和限时约束。

## 本地门禁

```text
mvn -Dtest=WeComPartyProfileServiceTest,WeComChatDataNormalizerTest,WeComChatDataStoreTest,WeComMessageProjectorTest,WeComConversationBackfillServiceTest test
npm run test:source
npm run test:ui
npm run build
rg -n 'user/list_id|get_corp_token' demo/message-center-spring/backend/src/main/java
```

## 服务器门禁

运行 `scripts/wecom-server-acceptance.sh`。脚本只输出进程、哈希、HTTP 状态、迁移版本和计数，不输出 token、secret、ticket 或正文。真实企业微信权限仍必须在已授权服务器上用该安装实例执行最小成员、客户群、ChatData 和 JSAPI 调用；本地不能替代该证据。

## 尚未宣称通过

- 未生成或部署 jar、前端 dist。
- 未执行真实企业微信上游权限验收。
- Docker/Testcontainers 不可用时，后端全量集成测试不能作为本轮通过依据。
