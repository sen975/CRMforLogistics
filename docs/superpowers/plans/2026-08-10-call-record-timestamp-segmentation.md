# 通话转录真实时间戳与可读性分段实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 使用 FunASR 的真实 VAD/句级区间展示通话时间轴，并允许历史空时间轴记录重新转录，同时只在显示层按中英文标点换行。

**Architecture:** FunASR 1.4.0 服务继续把 `sentence_info` 映射为 OpenAI `verbose_json.segments`，Spring adapter 只校验和持久化这些真实区间。状态机扩展现有 retry 合同以接纳 `completed + []`，React 通过纯显示 helper 分行并保持 segment 时间边界及原始字符不变。

**Tech Stack:** Java 17、Spring Boot 3.4、MyBatis-Plus、JUnit 5、AssertJ、Mockito、React 18、TypeScript 5.6、Ant Design 5、Node test runner、Vite 6

## Global Constraints

- 不根据音频总时长、文字长度、字符数或固定间隔推算时间戳。
- 不修改 `/Users/z/FunASR` 外部仓库，不切换 SenseVoice，不增加常驻模型。
- 只有 `failed` 或 `completed` 且机器转录 `segments` 为空的记录可以手动重新转录。
- 重新转录保留 `call_transcript_revisions` 和 `currentRevisionId`。
- 标点分行只影响渲染，所有行重新拼接后必须与 segment 原文逐字符相同。
- 不修改 ChatApp、WeCom、登录、联系人或其他线程文件。

---

### Task 1: 历史空时间轴记录重新入队

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordStateMachine.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordStateMachineTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordServiceTest.java`

**Interfaces:**
- Consumes: `CallRecordEntity.transcriptionState`、`transcriptionResultSegments`、`currentRevisionId`。
- Produces: `static boolean canManualRetry(CallRecordEntity)` 和扩展后的 `manualRetry(CallRecordEntity, Instant)`。

- [ ] **Step 1: 写状态机失败测试**

在 `CallRecordStateMachineTest` 增加三个测试：

```java
@Test
void requeuesCompletedTranscriptWithoutSegmentsAndPreservesRevision() {
    CallRecordEntity completed = completedRecord("[]");
    UUID revisionId = UUID.randomUUID();
    completed.setCurrentRevisionId(revisionId);
    completed.setTranscriptionResultOriginalText("旧机器原文");

    CallRecordEntity queued = CallRecordStateMachine.manualRetry(completed, NOW);

    assertThat(queued.getTranscriptionState()).isEqualTo("queued");
    assertThat(queued.getTranscriptionNextAttemptAt()).isEqualTo(NOW);
    assertThat(queued.getTranscriptionAttempts()).isZero();
    assertThat(queued.getCurrentRevisionId()).isEqualTo(revisionId);
    assertThat(queued.getTranscriptionResultOriginalText()).isEqualTo("旧机器原文");
}

@Test
void rejectsCompletedTranscriptThatAlreadyHasRealSegments() {
    CallRecordEntity completed = completedRecord(
            "[{\"startSeconds\":0.0,\"endSeconds\":1.0,\"text\":\"你好。\"}]");

    assertThatThrownBy(() -> CallRecordStateMachine.manualRetry(completed, NOW))
            .isInstanceOf(CallRecordException.class)
            .extracting(error -> ((CallRecordException) error).code())
            .isEqualTo("CALL_RECORD_STATE_INVALID");
}

@Test
void reportsOnlyFailedOrCompletedWithoutSegmentsAsRetryable() {
    assertThat(CallRecordStateMachine.canManualRetry(failedRecord())).isTrue();
    assertThat(CallRecordStateMachine.canManualRetry(completedRecord("[]"))).isTrue();
    assertThat(CallRecordStateMachine.canManualRetry(completedRecord(
            "[{\"startSeconds\":0.0,\"endSeconds\":1.0,\"text\":\"你好。\"}]"))).isFalse();
}
```

测试 helper 必须构造非空 `transcriptionAttempts` 和 `version`，避免测试通过于无效实体。

- [ ] **Step 2: 运行测试确认红灯**

Run:

```bash
cd demo/message-center-spring/backend
mvn -Dtest=CallRecordStateMachineTest test
```

Expected: FAIL，原因是 `canManualRetry` 尚不存在且 `manualRetry` 拒绝 `completed`。

- [ ] **Step 3: 实现唯一重试判定**

在 `CallRecordStateMachine` 中加入：

```java
static boolean canManualRetry(CallRecordEntity current) {
    if (current == null) return false;
    if ("failed".equals(current.getTranscriptionState())) return true;
    if (!"completed".equals(current.getTranscriptionState())) return false;
    String segments = current.getTranscriptionResultSegments();
    return segments == null || segments.isBlank() || "[]".equals(segments.trim());
}
```

把 `manualRetry` 的首行状态校验替换为：

```java
if (!canManualRetry(current)) {
    throw new CallRecordException(
            "CALL_RECORD_STATE_INVALID", 409,
            "Call record state does not allow this transition", false);
}
```

其余字段更新保持现有逻辑；不得清空机器结果、`currentRevisionId` 或 revision 表。

- [ ] **Step 4: 补充 service 合同测试**

在 `CallRecordServiceTest` 增加 `retryCompletedTranscriptWithoutSegmentsUsesPersistedVersion()`，构造 `completed`、`transcriptionResultSegments="[]"`、`version=6`，断言 `mapper.replace(retried, 6L)`、返回 `queued` 和 `version=7`。同时补一个带非空 segments 的 completed 记录，断言 code 为 `CALL_RECORD_STATE_INVALID` 且 `mapper.replace` 从未调用。

- [ ] **Step 5: 运行后端目标测试确认绿灯**

Run:

```bash
cd demo/message-center-spring/backend
mvn -Dtest=CallRecordStateMachineTest,CallRecordServiceTest,FunAsrClientTest,TranscriptionWorkerTest test
```

Expected: PASS；FunASR adapter 仍接受真实 `segments`，空 segments 仍为合法完整转录。

- [ ] **Step 6: 提交后端合同**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordStateMachine.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordStateMachineTest.java
git add demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordServiceTest.java
git commit -m "feat: retry transcripts without timestamps"
```

---

### Task 2: 详情时间轴按标点分行

**Files:**
- Create: `demo/message-center-spring/frontend/src/utils/callRecordTranscript.ts`
- Create: `demo/message-center-spring/frontend/test/call-record-transcript.test.mjs`
- Modify: `demo/message-center-spring/frontend/src/components/CallRecordDetail.tsx`

**Interfaces:**
- Consumes: `CallRecordResponse`、`TranscriptSegment.text`。
- Produces: `canRetryTranscription(record: CallRecordResponse): boolean`、`splitTranscriptDisplayLines(text: string): string[]`。

- [ ] **Step 1: 写纯函数失败测试**

新测试沿用 `call-record-revision.test.mjs` 的 TypeScript `transpileModule` 加载方式，覆盖：

```javascript
assert.deepEqual(
  splitTranscriptDisplayLines('你好。今天可以吗？Yes, it works. Next line!'),
  ['你好。', '今天可以吗？', 'Yes, it works.', ' Next line!'],
);
assert.equal(
  splitTranscriptDisplayLines('价格是12.5元。').join(''),
  '价格是12.5元。',
);
assert.equal(
  splitTranscriptDisplayLines('No punctuation here').join(''),
  'No punctuation here',
);
assert.equal(canRetryTranscription(failedRecord), true);
assert.equal(canRetryTranscription(completedWithoutSegments), true);
assert.equal(canRetryTranscription(completedWithSegments), false);
```

所有分行用例都增加 `lines.join('') === source` 断言，证明显示 helper 不改写字符。

- [ ] **Step 2: 运行前端测试确认红灯**

Run:

```bash
cd demo/message-center-spring/frontend
npm test -- --test-name-pattern="transcript display"
```

Expected: FAIL，原因是 `callRecordTranscript.ts` 尚不存在。

- [ ] **Step 3: 实现显示 helper**

创建 `callRecordTranscript.ts`：

```typescript
import type { CallRecordResponse } from '../api/types';

const HARD_BREAKS = new Set(['。', '！', '？', '；', '!', '?', ';']);

export function canRetryTranscription(record: CallRecordResponse): boolean {
  if (record.transcription.state === 'failed') return true;
  return record.transcription.state === 'completed'
    && (record.transcription.result?.segments.length ?? 0) === 0;
}

export function splitTranscriptDisplayLines(text: string): string[] {
  if (text.length === 0) return [''];
  const lines: string[] = [];
  let lineStart = 0;
  for (let index = 0; index < text.length; index += 1) {
    const current = text[index];
    const previous = text[index - 1] ?? '';
    const next = text[index + 1] ?? '';
    const decimalPoint = current === '.' && /\d/.test(previous) && /\d/.test(next);
    const englishPeriod = current === '.'
      && !decimalPoint
      && next !== '.'
      && (next === '' || /\s/.test(next));
    if (HARD_BREAKS.has(current) || englishPeriod) {
      lines.push(text.slice(lineStart, index + 1));
      lineStart = index + 1;
    }
  }
  if (lineStart < text.length) lines.push(text.slice(lineStart));
  return lines.length > 0 ? lines : [text];
}
```

函数不得 `trim()`、替换字符或删除空白。

- [ ] **Step 4: 接线详情组件**

在 `CallRecordDetail.tsx` 导入两个 helper：

```tsx
import { canRetryTranscription, splitTranscriptDisplayLines } from '../utils/callRecordTranscript';
```

把重转录按钮条件改成 `canRetryTranscription(record)`。时间轴的 segment 文本改为同一时间标签下的多行内容：

```tsx
<Text style={{ fontSize: 12, whiteSpace: 'pre-wrap' }}>
  {splitTranscriptDisplayLines(seg.text).map((line, index) => (
    <span key={`${index}-${line.length}`}>
      {index > 0 && <br />}
      {line}
    </span>
  ))}
</Text>
```

保留现有 List、时间 Tag 和侧栏布局，不新增卡片、说明文案或页面跳转。

- [ ] **Step 5: 运行前端测试和构建**

Run:

```bash
cd demo/message-center-spring/frontend
npm test
npm run build
```

Expected: 全部测试 PASS，TypeScript 与 Vite 构建成功且无 warning。

- [ ] **Step 6: 提交前端显示**

```bash
git add demo/message-center-spring/frontend/src/utils/callRecordTranscript.ts
git add demo/message-center-spring/frontend/test/call-record-transcript.test.mjs
git add demo/message-center-spring/frontend/src/components/CallRecordDetail.tsx
git commit -m "feat: display timestamped transcript lines"
```

---

### Task 3: 回归与真实录音验收

**Files:**
- Modify: `docs/superpowers/plans/2026-08-10-call-record-timestamp-segmentation.md`（只回填实际命令和结果）

**Interfaces:**
- Consumes: 本机 FunASR `http://127.0.0.1:8000/v1/audio/transcriptions`、Spring retry API、PostgreSQL `call_records`。
- Produces: 一条历史空时间轴记录的真实 segments 运行时证据。

- [ ] **Step 1: 运行后端回归**

Run:

```bash
cd demo/message-center-spring/backend
mvn -Dtest='!AppIntegrationTest' test
```

Expected: 全部测试 PASS，无新增 warning。`AppIntegrationTest` 仅在本机 Testcontainers 条件满足时另跑。

- [ ] **Step 2: 检查 FunASR 健康与进程合同**

Run:

```bash
curl -sS http://127.0.0.1:8000/health
```

Expected: `status=ok`、`device=cpu`、`sensevoice` 已加载。实际进程必须仍为 `examples/openai_api/server.py --model sensevoice`。

- [ ] **Step 3: 对历史空时间轴记录执行重新转录**

先从电话仓库打开一条 `completed` 且时间轴为空的历史记录，记录页面 URL/详情响应中的 UUID。然后在已登录详情侧栏点击“重新转录”，等待状态从 `queued`、`processing` 变为 `completed`。不得直接改数据库状态，也不得重新上传音频生成新记录。

- [ ] **Step 4: 核对真实区间**

只读查询该记录：

```sql
SELECT transcription_state,
       transcription_result_original_text,
       transcription_result_segments,
       current_revision_id
FROM call_records
WHERE id = :call_record_id::uuid;
```

`:call_record_id` 绑定 Step 3 记录的 UUID。Expected: state 为 `completed`；segments 非空，每项时间有限、单调且不超过真实音频时长；`current_revision_id` 与重转录前一致。若 FunASR 仍返回空 segments，则如实记录运行时不满足，不能生成或补写伪时间戳。

- [ ] **Step 5: 真实页面检查**

通过内置浏览器打开详情侧栏，确认时间标签和标点分行同时出现，音频首次播放正常，人工修订稿仍存在。若内置浏览器不可用，记录该限制并由用户侧实测，不得声称已完成 UI 点击验收。

- [ ] **Step 6: 格式与 git 边界复核**

Run:

```bash
git diff --check -- demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord demo/message-center-spring/frontend/src/components/CallRecordDetail.tsx demo/message-center-spring/frontend/src/utils/callRecordTranscript.ts demo/message-center-spring/frontend/test/call-record-transcript.test.mjs docs/superpowers/plans/2026-08-10-call-record-timestamp-segmentation.md
git status --short
```

Expected: 本任务文件无空白错误；其他线程的 dirty 文件仍保持原样且未被暂存。

- [ ] **Step 7: 回填验收并提交计划结果**

只在本计划末尾追加本轮实际运行命令、通过数量、真实录音 UUID（不含凭证）和未闭合限制，然后仅暂存本计划：

```bash
git add docs/superpowers/plans/2026-08-10-call-record-timestamp-segmentation.md
git commit -m "docs: verify timestamped call transcripts"
```
