# FunASR 空分段响应兼容设计

**日期：** 2026-08-07  
**状态：** 已确认  
**适用范围：** `demo/message-center-spring/` 电话录音自动转录

## 1. 问题与证据

Spring 后端已能自动领取录音并调用 FunASR，但当前 `sensevoice` 服务返回以下响应：

```json
{
  "text": "完整转录文本",
  "segments": [],
  "duration": 3.11,
  "model": "sensevoice"
}
```

同一 MP3 的真实时长为 62.54 秒。FunASR 的 OpenAPI 文档将 `duration` 定义为服务端转录延迟，`server.py` 也直接写入推理耗时 `elapsed`；它不是音频时长。`sensevoice` 结果不包含 `sentence_info` 时，FunASR 会合法返回空 `segments`。

当前 Spring `FunAsrClient` 要求分段非空，并把 FunASR 的 `duration` 当作音频时长，因此抛出 `FUNASR_INVALID_RESPONSE: FunASR segment count is invalid`，丢弃了已经生成的完整文本。

## 2. 设计决策

采用以下唯一主线：

- 非空 `text` 是转录成功的最低合同。
- `segments` 必须存在且为数组，但允许为空；空数组明确表示当前模型没有提供时间轴分段。
- 不为缺失的模型分段补造整段时间戳，也不根据文字长度推测时间。
- 转录结果的 `durationSeconds` 使用上传阶段已经校验并持久化的 `audioDurationSeconds`。
- FunASR 响应中的 `duration` 是推理延迟，不进入电话录音领域结果。
- 非空分段仍执行数量、文本、顺序和起止时间校验，时间边界使用真实音频时长。

不修改外部 `/Users/z/FunASR` 仓库或容器。本轮由 Spring 的 FunASR adapter 映射外部协议差异，电话记录核心只消费规范化后的领域结果。

## 3. Owner 与数据流

`MinioAudioStore` 在上传时校验 MP3 并生成可信的 `audioDurationSeconds`，该值随 `CallRecordEntity` 持久化。

```text
CallRecordEntity.audioDurationSeconds
  -> TranscriptionWorker
  -> FunAsrClient.transcribe(path, model, audioDurationSeconds)
  -> FunASR HTTP response: text + optional empty segments + latency
  -> normalized TranscriptionResult
  -> CallRecordStateMachine.complete
  -> PostgreSQL
  -> CallRecordDetail
```

职责边界：

- `TranscriptionWorker` 只传递记录中已有的真实音频时长。
- `FunAsrClient` 拥有外部响应解析与规范化，不拥有重试或状态转换。
- `CallRecordStateMachine` 校验规范化领域结果，允许空分段但拒绝 null、超限或非法分段。
- `CallRecordDetail` 继续显示完整原文；仅当分段非空时显示时间轴，现有前端行为无需修改。

## 4. 错误处理

以下情况仍返回不可重试的 `FUNASR_INVALID_RESPONSE`：

- `text` 缺失、空白或超过上限。
- `segments` 缺失、不是数组或超过数量上限。
- 任一非空分段缺少文本或时间字段。
- 分段时间为负、倒序、相互乱序或超出真实音频时长容差。
- 记录中的真实音频时长为空、非有限数、非正数或超过系统上限。

空 `segments` 本身不再视为错误。网络、超时和 HTTP 状态的现有重试语义保持不变。

## 5. 历史失败记录

现有 5 条记录已经处于 `failed`，错误为不可重试。代码部署并重启后，它们不会自行从 `failed` 变回 `queued`；需要通过现有“重新转录”命令逐条重新入队。重新入队后将使用新合同保存完整文本。

不直接批量修改数据库状态，避免绕过现有状态机、幂等键和审计边界。

## 6. 测试与验收

新增回归测试覆盖：

- `text` 非空且 `segments=[]` 时解析成功。
- 结果使用记录提供的真实音频时长，而不是响应延迟。
- 有分段响应仍按真实时长校验并正常解析。
- `segments` 缺失、类型错误、超限或分段非法时仍失败。
- 状态机接受空分段，并序列化为 `[]`。
- Worker 把记录中的 `audioDurationSeconds` 传给 adapter。

验收命令：

```bash
cd demo/message-center-spring/backend
mvn -Dtest=FunAsrClientTest,TranscriptionWorkerTest,CallRecordStateMachineTest test
mvn -Dtest='!AppIntegrationTest' test
```

完成标准：目标测试和非 Testcontainers 后端测试全部通过，`git diff --check` 无格式问题；重启后手动重新转录一条历史记录，数据库状态进入 `completed`，详情展示完整原文且不显示虚构时间轴。

