# FunASR Empty Segments Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保存 FunASR `sensevoice` 返回的完整文本，即使没有时间轴分段，并使用电话记录中可信的真实音频时长。

**Architecture:** `TranscriptionWorker` 把 `CallRecordEntity.audioDurationSeconds` 显式传给 `FunAsrClient`。adapter 只把 FunASR 的 `text`、`model` 和可选分段映射为领域结果，忽略其代表推理延迟的 `duration`；状态机允许空分段并继续严格校验非空分段。

**Tech Stack:** Java 17、Spring Boot 3.4、Jackson、JUnit 5、AssertJ、Mockito、Maven

## Global Constraints

- 非空 `text` 是转录成功的最低合同。
- `segments` 必须存在且为数组，可以为空，但不能超过 20000 项。
- 不伪造整段时间戳，不根据文字长度推测分段。
- `durationSeconds` 必须来自已校验的 `CallRecordEntity.audioDurationSeconds`。
- 非空分段继续执行文本、顺序、起止时间和真实音频时长边界校验。
- 不修改外部 `/Users/z/FunASR` 仓库或容器。
- 不直接修改 5 条历史失败记录的数据库状态。

---

### Task 1: 规范化空分段 FunASR 结果

**Files:**
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/FunAsrClientTest.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordStateMachineTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/TranscriptionWorkerTest.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/FunAsrClient.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordStateMachine.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/TranscriptionWorker.java`

**Interfaces:**
- Consumes: `CallRecordEntity.getAudioDurationSeconds()`，上传阶段已经校验并持久化的真实 MP3 时长。
- Produces: `FunAsrClient.transcribe(Path audioPath, String model, double audioDurationSeconds)`。
- Produces: package-private `FunAsrClient.parseResponse(byte[] bytes, double audioDurationSeconds)`，供 adapter 单元测试验证响应映射。
- Preserves: `TranscriptionResult.segments()` 始终非 null；无模型分段时为不可变空列表。

- [x] **Step 1: 写 FunASR 响应失败测试**

创建 `FunAsrClientTest`，使用本次真实响应结构验证空分段和真实时长：

```java
@Test
void acceptsTextWithoutSegmentsAndUsesTrustedAudioDuration() {
    FunAsrClient client = new FunAsrClient(new FunAsrConfig(
            "http://127.0.0.1:8000", "sensevoice",
            Duration.ofSeconds(3), Duration.ofSeconds(30)));
    byte[] response = """
            {"text":"完整转录文本","segments":[],"language":"auto",
             "duration":3.11,"model":"sensevoice"}
            """.getBytes(StandardCharsets.UTF_8);

    TranscriptionResult result = client.parseResponse(response, 62.54);

    assertThat(result.originalText()).isEqualTo("完整转录文本");
    assertThat(result.durationSeconds()).isEqualTo(62.54);
    assertThat(result.segments()).isEmpty();
}
```

再增加一个非空分段测试，断言分段结束时间按传入的 62.54 秒校验，而不是响应的 3.11 秒。

- [x] **Step 2: 写状态机空分段失败测试**

创建 `CallRecordStateMachineTest`，构造持有有效租约的 `processing` 实体，并提交：

```java
new TranscriptionResult("sensevoice", 62.54, "完整转录文本", List.of(), NOW)
```

断言状态变为 `completed`，`transcriptionResultSegments` 等于 `[]`，原文和真实时长被保存。

- [x] **Step 3: 更新 Worker 合同测试**

在 `TranscriptionWorkerTest` 中把转录 mock 改为：

```java
when(transcriber.transcribe(any(), eq("sensevoice"), eq(10.0)))
        .thenReturn(transcriptionResult());
```

失败路径使用同一三参数签名，确保 Worker 明确传递记录中的 `audioDurationSeconds`。

- [x] **Step 4: 运行目标测试确认失败**

Run:

```bash
cd demo/message-center-spring/backend
mvn -Dtest=FunAsrClientTest,CallRecordStateMachineTest,TranscriptionWorkerTest test
```

Expected: FAIL；原因包括 `parseResponse` 不可访问或缺少真实时长参数、状态机拒绝空分段、Worker 仍调用双参数 `transcribe`。

- [x] **Step 5: 修改 FunASR adapter**

将公开调用改为：

```java
public TranscriptionResult transcribe(Path audioPath, String model,
                                      double audioDurationSeconds)
```

请求前校验 `audioDurationSeconds` 有限、`> 0` 且 `<= 7200`。读取响应后调用：

```java
return parseResponse(stream.readAllBytes(), audioDurationSeconds);
```

`parseResponse` 保持结构化 JSON 校验，但按以下规则映射：

```java
TranscriptionResult parseResponse(byte[] bytes, double audioDurationSeconds) {
    // text/model/segments 类型和上界校验保持现有逻辑
    // 不再把响应 duration 映射成领域音频时长
    if (segmentsElement.size() > maxSegments) {
        throw invalidResponse("FunASR segment count is invalid", null);
    }
    // 空数组合法；非空项继续按 audioDurationSeconds 校验
    return new TranscriptionResult(
            responseModel, audioDurationSeconds, text, List.copyOf(segments), Instant.now());
}
```

响应 `duration` 可以存在，但不作为领域事实读取或校验。

- [x] **Step 6: 修改领域状态机**

把结果校验中的分段条件从：

```java
result.segments() == null || result.segments().isEmpty()
        || result.segments().size() > MAX_SEGMENTS
```

改为：

```java
result.segments() == null || result.segments().size() > MAX_SEGMENTS
```

保留现有非空分段循环校验与 `segmentsToJson`；空列表自然序列化为 `[]`。

- [x] **Step 7: 修改 Worker 接线**

调用 adapter 时显式传递记录中的时长：

```java
TranscriptionResult result = transcriber.transcribe(
        path, leased.getTranscriptionModel(), leased.getAudioDurationSeconds());
```

- [x] **Step 8: 运行目标测试确认通过**

Run:

```bash
cd demo/message-center-spring/backend
mvn -Dtest=FunAsrClientTest,CallRecordStateMachineTest,TranscriptionWorkerTest test
```

Expected: 目标测试全部 PASS，无新增编译告警。

- [x] **Step 9: 运行后端回归与格式门禁**

Run:

```bash
cd demo/message-center-spring/backend
mvn -Dtest='!AppIntegrationTest' test
cd ../../..
git diff --check -- \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord
```

Result: 2026-08-07 运行 91 项测试，全部 PASS；本任务 callrecord 源码与测试未发现空白格式问题。`AppIntegrationTest` 未纳入本轮命令，因为它依赖本机 Testcontainers/Docker 运行环境。

- [ ] **Step 10: 运行时验收**

重启 Spring 后端后，通过现有“重新转录”命令把一条 `failed` 记录重新入队。只读查询确认：

```sql
SELECT transcription_state,
       transcription_result_duration_seconds,
       transcription_result_original_text,
       transcription_result_segments
FROM call_records
WHERE id = '<retried-id>'::uuid;
```

Expected: `completed`、时长约 62.54 秒、原文非空、分段为 `[]`；详情展示原文且不显示时间轴。

- [ ] **Step 11: 提交实现**

只暂存本任务列出的 6 个源码和测试文件以及本计划：

```bash
git add docs/superpowers/plans/2026-08-07-funasr-empty-segments.md \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/FunAsrClient.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordStateMachine.java \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/TranscriptionWorker.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/FunAsrClientTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordStateMachineTest.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/TranscriptionWorkerTest.java
git commit -m "fix: accept FunASR transcripts without segments"
```
