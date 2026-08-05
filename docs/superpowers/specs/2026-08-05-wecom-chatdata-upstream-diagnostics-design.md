# 企业微信专区调用上游诊断设计

## 1. 目标

为 `demo/message-center-demo` 的 `sync_call_program` 调用增加显式诊断模式。在企业微信返回非零
`errcode` 时，运维人员可以从服务器标准错误看到完整、受上界约束的 `errmsg`，同时现有审计和
前端错误合同继续只暴露结构化错误与安全 `hint`。

本轮不修改专区 rootfs 镜像，不改变同步请求、消息解密、存储或前端接口。

## 2. 当前问题

`WeComChatDataGateway` 当前只读取外层 `errcode`，并在构造 `WeComChatDataException` 时把
`upstreamHint` 固定为 `null`。企业微信响应中的 `errmsg` 因此既不能用于提取 `hint: [...]`，也
没有受控的服务器诊断出口。`wecom-viewer-audit.jsonl` 最终只能显示错误码、路径和 HTTP 状态。

## 3. 唯一 owner

- `WeComChatDataGateway` 拥有 `sync_call_program` 响应解析和诊断事件产生。
- `Config` 拥有诊断开关读取和默认值。
- `WeComViewerAuditTrail` 只消费异常中的安全 `upstreamHint`，不拥有 `errmsg`。
- Controller、前端和专区程序不得解析或展示该诊断字段。

## 4. 配置合同

新增配置：

```env
WECOM_CHATDATA_DIAGNOSTICS=false
```

默认关闭。只有显式设置为 `true` 时才允许输出完整 `errmsg`。该配置用于短时真实链路排障，排障
完成后必须关闭。

## 5. 数据流

企业微信返回 HTTP 2xx 且外层 `errcode != 0` 时：

1. 读取字符串 `errmsg`，最多接受 4096 个 UTF-8 字节。
2. 从 `errmsg` 中提取格式受限的 `hint: [A-Za-z0-9_-]`，最多 128 个字符。
3. 将 `errcode`、路径、HTTP 状态和安全 hint 写入 `WeComChatDataException`。
4. 诊断开关开启时，向服务器 `stderr` 输出单行结构化 JSON，字段固定为
   `event`、`path`、`httpStatus`、`errcode`、`errmsg`。
5. 现有 viewer 审计继续只写 `upstreamHint`，不得写完整 `errmsg`。

诊断事件不包含 URI query、access token、请求体、`response_data`、密钥、密文、用户 ID 或联系人
ID。JSON 必须由结构化序列化器生成，禁止字符串拼接。

## 6. 错误和边界

- `errmsg` 缺失、类型错误或超过 4096 个 UTF-8 字节时，不把不可信内容写入诊断日志；原始
  `errcode` 仍按现有 `WECOM_CHATDATA_PROGRAM_ERROR` 返回。
- 诊断输出失败不得替代主异常，也不得改变同步结果。
- 诊断关闭时不得向 `stderr` 输出上游 `errmsg`。
- 完整 `errmsg` 不进入普通审计文件、HTTP 响应、浏览器或持久业务数据。

## 7. 测试与验收

测试先行覆盖：

- 非零外层错误包含 `hint: [trace123]` 时，异常与审计获得 `trace123`。
- 诊断开启时，`stderr` 事件包含完整 `errmsg` 和结构化元数据。
- 诊断关闭时，`stderr` 不包含 `errmsg`。
- 缺失、非法类型和超限 `errmsg` 不泄漏内容且仍返回稳定错误。
- access token、请求体和 `response_data` 不出现在诊断事件中。

验收命令：

```text
mvn -q -Dtest=WeComChatDataGatewayTest,WeComViewerAuditTrailTest test
mvn -q test
mvn -q -DskipTests package
git diff --check
```
