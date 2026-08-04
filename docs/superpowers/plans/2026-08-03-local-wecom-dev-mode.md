# 本地企业微信开发模式实施计划

## 目标

为消息中心增加一个仅供本机开发使用的企业微信替身运行面，绕过扫码、Suite、access token、RSA 和专区程序调用，使前端可以用 fixture 或本地 JSONL 验证登录后的消息列表、同步、会话选择和会话详情流程。

## 边界

- `LOCAL_DEV_MODE` 默认关闭，生产配置不改变。
- 开启时服务必须绑定回环地址；非回环监听直接拒绝启动。
- 本地模式不读取或调用企业微信凭证、授权安装记录、真实 HTTP API、JS-SDK 或专区程序。
- 保留 viewer token、过期、一次性 session 和联系人可见性校验语义。
- 不修改企业微信后台能力协议、镜像或线上审核版本。

## 实施步骤

1. 配置层增加 `LOCAL_DEV_MODE`、`LOCAL_WECOM_DATA_SOURCE`、`LOCAL_WECOM_DATA_FILE` 和 `WEB_BIND_ADDRESS`，校验本地模式只能使用回环监听。
2. 新增 `LocalWeComDevelopmentService`，拥有本地 attempt、viewer token、fixture/JSONL 数据读取、同步结果和一次性会话生命周期。
3. 在启动与 HTTP 路由接线本地服务；本地模式跳过真实授权服务、会话同步网关和 JS SDK 配置。
4. 页面注入本地模式标志：直接完成本地登录，跳过企业微信脚本，并将会话详情渲染为可读的本地消息引用列表。
5. 增加配置、服务和 HTTP 路由测试，并验证生产默认仍走原有路径。

## 验收

```bash
cd demo/message-center-demo
mvn -q -Dtest=ConfigTest,LocalWeComDevelopmentServiceTest,UnifiedMessageStoreTest test
mvn -q test
mvn -q test-compile
mvn -q -DskipTests package
node contracts/openapi/message-center-v1.test.mjs
git diff --check
```

停止条件：本地模式可以通过 HTTP 完成 attempts -> exchange -> sync -> session create -> session read；`LOCAL_DEV_MODE` 未设置时现有企业微信测试全部保持通过；不能证明的真实企业微信链路不宣称已验证。
