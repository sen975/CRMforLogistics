# 通话转录修订保存冲突 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复通话详情人工修订使用过期版本保存失败的问题，同时保留真正的并发修订保护，并确保后端修订写入原子化。

**Architecture:** 前端把“编辑时的 `currentRevisionId`”作为人工修订基线，保存前重新请求最新详情；基线未变化时使用最新 `version` 保存，基线变化时保留草稿并报告冲突。后端用 Spring 事务包住修订插入与主记录乐观锁更新，避免并发失败留下孤立修订。

**Tech Stack:** React 18、TanStack Query 5、Axios、TypeScript 5.6、Node test runner、Spring Boot 3.4、MyBatis-Plus、JUnit 5、Mockito

## Global Constraints

- 不取消或绕过后端乐观锁。
- 不在 `currentRevisionId` 已变化时自动覆盖最新人工修订。
- 网络、服务端和并发错误都必须保留用户草稿与编辑状态。
- 不新增数据库字段，不改变修订历史合同。
- 不改变右侧详情栏布局，不引入新弹窗或独立页面。
- 不触碰 ChatApp、WeCom、邮件、登录和联系人模块。
- 当前 worktree 很脏，只提交本计划列出的精确路径。

---

### Task 1: 人工修订保存版本决策

**Files:**
- Create: `demo/message-center-spring/frontend/src/utils/callRecordRevision.ts`
- Create: `demo/message-center-spring/frontend/test/call-record-revision.test.mjs`
- Modify: `demo/message-center-spring/frontend/src/api/types.ts:115-153`

**Interfaces:**
- Consumes: `CallRecordResponse.currentRevisionId: string | null` 与 `CallRecordResponse.version: number`。
- Produces: `decideTranscriptSave(baseRevisionId, latest): { kind: 'save'; expectedVersion: number } | { kind: 'conflict' }`。

- [ ] **Step 1: 写失败测试**

创建 `test/call-record-revision.test.mjs`，通过 TypeScript 编译器加载纯函数并验证三种决策：

```javascript
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import ts from 'typescript';

const source = readFileSync(
  new URL('../src/utils/callRecordRevision.ts', import.meta.url),
  'utf8',
);
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const loadedModule = { exports: {} };
new Function('module', 'exports', compiled)(loadedModule, loadedModule.exports);
const { decideTranscriptSave } = loadedModule.exports;

test('uses the latest record version when the transcript revision is unchanged', () => {
  assert.deepEqual(
    decideTranscriptSave('revision-1', { currentRevisionId: 'revision-1', version: 7 }),
    { kind: 'save', expectedVersion: 7 },
  );
});

test('rejects automatic save when another transcript revision was created', () => {
  assert.deepEqual(
    decideTranscriptSave('revision-1', { currentRevisionId: 'revision-2', version: 8 }),
    { kind: 'conflict' },
  );
});

test('treats two null revision ids as the same transcript baseline', () => {
  assert.deepEqual(
    decideTranscriptSave(null, { currentRevisionId: null, version: 6 }),
    { kind: 'save', expectedVersion: 6 },
  );
});
```

- [ ] **Step 2: 运行测试确认 RED**

Run:

```bash
cd demo/message-center-spring/frontend
node --test test/call-record-revision.test.mjs
```

Expected: FAIL，错误说明 `src/utils/callRecordRevision.ts` 不存在。

- [ ] **Step 3: 实现最小版本决策函数并修正响应类型**

创建 `src/utils/callRecordRevision.ts`：

```typescript
interface TranscriptRevisionSnapshot {
  currentRevisionId: string | null;
  version: number;
}

export type TranscriptSaveDecision =
  | { kind: 'save'; expectedVersion: number }
  | { kind: 'conflict' };

export function decideTranscriptSave(
  baseRevisionId: string | null,
  latest: TranscriptRevisionSnapshot,
): TranscriptSaveDecision {
  if (latest.currentRevisionId !== baseRevisionId) {
    return { kind: 'conflict' };
  }
  return { kind: 'save', expectedVersion: latest.version };
}
```

把 `CallRecordResponse.currentRevisionId` 从 `string` 改成 `string | null`，与后端真实响应一致。

- [ ] **Step 4: 运行测试和类型构建确认 GREEN**

Run:

```bash
cd demo/message-center-spring/frontend
node --test test/call-record-revision.test.mjs
npm run build
```

Expected: 3 个目标测试 PASS，TypeScript 与 Vite build 成功且无 warning。

- [ ] **Step 5: 按路径提交 Task 1**

```bash
git add -- \
  demo/message-center-spring/frontend/src/utils/callRecordRevision.ts \
  demo/message-center-spring/frontend/src/api/types.ts \
  demo/message-center-spring/frontend/test/call-record-revision.test.mjs
git commit --only -m "fix: define call transcript save version decision" -- \
  demo/message-center-spring/frontend/src/utils/callRecordRevision.ts \
  demo/message-center-spring/frontend/src/api/types.ts \
  demo/message-center-spring/frontend/test/call-record-revision.test.mjs
```

---

### Task 2: 通话详情保存前刷新与错误投影

**Files:**
- Modify: `demo/message-center-spring/frontend/src/components/CallRecordDetail.tsx:1-110`
- Modify: `demo/message-center-spring/frontend/test/call-record-side-panel.test.mjs`

**Interfaces:**
- Consumes: Task 1 的 `decideTranscriptSave` 与现有 `fetchCallRecord`、`reviseTranscript`。
- Produces: 保存前刷新、人工修订冲突保护、请求期间按钮禁用及错误码区分。

- [ ] **Step 1: 扩展组件接线失败测试**

在 `call-record-side-panel.test.mjs` 中读取 `CallRecordDetail.tsx` 源码，并新增：

```javascript
const callRecordDetailSource = readFileSync(
  new URL('../src/components/CallRecordDetail.tsx', import.meta.url),
  'utf8',
);

test('call record transcript save refreshes the record before optimistic update', () => {
  assert.match(callRecordDetailSource, /await fetchCallRecord\(callRecordId\)/);
  assert.match(callRecordDetailSource, /decideTranscriptSave\(/);
  assert.ok(
    callRecordDetailSource.indexOf('await fetchCallRecord(callRecordId)')
      < callRecordDetailSource.indexOf('await reviseTranscript(callRecordId'),
  );
  assert.match(callRecordDetailSource, /TRANSCRIPT_VERSION_CONFLICT/);
  assert.match(callRecordDetailSource, /loading=\{savingTranscript\}/);
});
```

- [ ] **Step 2: 运行测试确认 RED**

Run:

```bash
cd demo/message-center-spring/frontend
node --test test/call-record-side-panel.test.mjs
```

Expected: 新测试 FAIL，缺少保存前 `fetchCallRecord`、决策函数、错误码与 loading 状态。

- [ ] **Step 3: 实现保存前刷新与冲突保护**

修改 `CallRecordDetail.tsx`：

```typescript
import axios from 'axios';
import { fetchCallRecord, createAudioSession, retryCallRecord, reviseTranscript, reviseNote } from '../api/endpoints';
import type { ApiError, CallRecordResponse } from '../api/types';
import { decideTranscriptSave } from '../utils/callRecordRevision';
```

增加状态：

```typescript
const [transcriptBaseRevisionId, setTranscriptBaseRevisionId] = useState<string | null>(null);
const [savingTranscript, setSavingTranscript] = useState(false);
```

增加错误读取函数：

```typescript
const apiError = (error: unknown): ApiError | undefined =>
  axios.isAxiosError<ApiError>(error) ? error.response?.data : undefined;
```

替换保存函数：

```typescript
const handleSaveTranscript = async () => {
  if (savingTranscript) return;
  setSavingTranscript(true);
  try {
    const latest = await fetchCallRecord(callRecordId);
    queryClient.setQueryData(['callRecord', callRecordId], latest);
    const decision = decideTranscriptSave(transcriptBaseRevisionId, latest);
    if (decision.kind === 'conflict') {
      message.error('转录稿已被更新，请核对最新内容后重新编辑');
      return;
    }
    const updated = await reviseTranscript(callRecordId, {
      text: transcriptDraft,
      expectedVersion: decision.expectedVersion,
    });
    queryClient.setQueryData(['callRecord', callRecordId], updated);
    setEditingTranscript(false);
    setTranscriptBaseRevisionId(updated.currentRevisionId);
    message.success('转录稿已保存');
  } catch (error) {
    const failure = apiError(error);
    if (failure?.code === 'TRANSCRIPT_VERSION_CONFLICT') {
      await queryClient.invalidateQueries({ queryKey: ['callRecord', callRecordId] });
      message.error('转录稿已被更新，请核对最新内容后重新编辑');
    } else {
      message.error(failure?.message || '转录稿保存失败');
    }
  } finally {
    setSavingTranscript(false);
  }
};
```

编辑入口同时保存基线：

```typescript
setTranscriptBaseRevisionId(record.currentRevisionId);
setEditingTranscript(true);
```

保存按钮增加：

```tsx
loading={savingTranscript}
disabled={savingTranscript}
```

取消编辑时只退出编辑状态，不清空 `transcriptDraft`，保证请求失败或用户误点后仍能恢复草稿。

- [ ] **Step 4: 运行前端测试与构建确认 GREEN**

Run:

```bash
cd demo/message-center-spring/frontend
npm test
npm run build
```

Expected: 全部 Node 测试 PASS，TypeScript 与 Vite build 成功且无 warning。

- [ ] **Step 5: 按路径提交 Task 2**

```bash
git add -- \
  demo/message-center-spring/frontend/src/components/CallRecordDetail.tsx \
  demo/message-center-spring/frontend/test/call-record-side-panel.test.mjs
git commit --only -m "fix: refresh call record before transcript save" -- \
  demo/message-center-spring/frontend/src/components/CallRecordDetail.tsx \
  demo/message-center-spring/frontend/test/call-record-side-panel.test.mjs
```

---

### Task 3: 后端人工修订事务原子性

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordService.java:120-160`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordServiceTest.java`

**Interfaces:**
- Consumes: `CallRecordService.revise(UUID, String, String, long)` 与 `CallRecordMapper.replace(entity, expectedVersion)`。
- Produces: `revise` 的 Spring 事务边界，以及成功路径使用持久化旧版本执行乐观锁更新的回归证明。

- [ ] **Step 1: 写后端失败测试**

在 `CallRecordServiceTest` 增加 import：

```java
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.Mockito.verify;
```

新增测试：

```java
@Test
void reviseIsTransactionalSoRevisionInsertRollsBackWithVersionConflict() throws Exception {
    assertThat(AnnotatedElementUtils.hasAnnotation(
            CallRecordService.class.getMethod(
                    "revise", UUID.class, String.class, String.class, long.class),
            Transactional.class)).isTrue();
}

@Test
void reviseUsesPersistedVersionAndReturnsIncrementedVersion() {
    CallRecordMapper mapper = mock(CallRecordMapper.class);
    CallTranscriptRevisionMapper revisionMapper = mock(CallTranscriptRevisionMapper.class);
    CallRecordEntity current = completedRecord(6L);
    UUID id = current.getId();
    when(mapper.findById(id)).thenReturn(Optional.of(current));
    when(revisionMapper.listByCallRecordId(id)).thenReturn(java.util.List.of());
    when(revisionMapper.insert(any(CallTranscriptRevisionEntity.class))).thenReturn(1);
    when(mapper.replace(any(CallRecordEntity.class), eq(6L))).thenReturn(1);
    CallRecordService service = service(mapper, revisionMapper);

    CallRecordEntity revised = service.revise(id, "人工修订", "user-1", 6L);

    assertThat(revised.getVersion()).isEqualTo(7L);
    assertThat(revised.getCurrentRevisionId()).isNotNull();
    verify(mapper).replace(revised, 6L);
    verify(revisionMapper).insert(any(CallTranscriptRevisionEntity.class));
}
```

在测试类中增加完整 helper：

```java
private static CallRecordService service(CallRecordMapper mapper,
                                         CallTranscriptRevisionMapper revisionMapper) {
    return new CallRecordService(
            mapper,
            revisionMapper,
            mock(MinioAudioStore.class),
            mock(ContactIdentityMapper.class),
            config(),
            new FunAsrConfig(
                    "http://127.0.0.1:8000", "sensevoice",
                    java.time.Duration.ofSeconds(3),
                    java.time.Duration.ofSeconds(30)),
            Clock.fixed(NOW, ZoneOffset.UTC));
}

private static CallRecordEntity completedRecord(long version) {
    CallRecordEntity record = new CallRecordEntity();
    record.setId(UUID.randomUUID());
    record.setTranscriptionState("completed");
    record.setVersion(version);
    return record;
}
```

同时让现有 `retryUsesPersistedVersionBeforeStateMachineIncrementsIt` 复用 `service(mapper, revisionMapper)`，只减少测试构造重复，不改变生产行为。

- [ ] **Step 2: 运行测试确认 RED**

Run:

```bash
cd demo/message-center-spring/backend
mvn -Dtest=CallRecordServiceTest test
```

Expected: 事务测试 FAIL，因为 `CallRecordService.revise` 尚未标注 `@Transactional`；成功路径测试应通过或只暴露现有构造差异。

- [ ] **Step 3: 为人工修订增加最小事务边界**

在 `CallRecordService.java` 增加：

```java
import org.springframework.transaction.annotation.Transactional;
```

并标注方法：

```java
@Transactional
public CallRecordEntity revise(UUID id, String text, String actor, long expectedVersion) {
```

方法继续使用请求提供的 `expectedVersion` 调用 `mapper.replace`，不改变状态机或数据库合同。

- [ ] **Step 4: 运行后端目标测试和通话记录回归**

Run:

```bash
cd demo/message-center-spring/backend
mvn -Dtest=CallRecordServiceTest,CallRecordStateMachineTest,CallRecordControllerTest,FunAsrClientTest,TranscriptionWorkerTest test
```

Expected: 所有目标测试 PASS，无 warning、失败或跳过。

- [ ] **Step 5: 按路径提交 Task 3**

```bash
git add -- \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordService.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordServiceTest.java
git commit --only -m "fix: make transcript revision writes atomic" -- \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordService.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordServiceTest.java
```

---

### Task 4: 完整验收与 Git 边界复核

**Files:**
- Verify only: Task 1-3 列出的文件

**Interfaces:**
- Consumes: 前端保存流程、后端事务修订与全部目标测试。
- Produces: 可交付的构建、测试和边界证据。

- [ ] **Step 1: 运行完整前端验收**

```bash
cd demo/message-center-spring/frontend
npm test
npm run build
```

Expected: 全部测试 PASS，构建 exit 0，无 warning。

- [ ] **Step 2: 运行后端通话记录验收**

```bash
cd demo/message-center-spring/backend
mvn -Dtest=CallRecordServiceTest,CallRecordStateMachineTest,CallRecordControllerTest,FunAsrClientTest,TranscriptionWorkerTest test
```

Expected: 全部目标测试 PASS，无 warning、失败或跳过。

- [ ] **Step 3: 检查格式和任务文件边界**

```bash
git diff --check
git status --short
git diff -- \
  demo/message-center-spring/frontend/src/utils/callRecordRevision.ts \
  demo/message-center-spring/frontend/src/api/types.ts \
  demo/message-center-spring/frontend/src/components/CallRecordDetail.tsx \
  demo/message-center-spring/frontend/test/call-record-revision.test.mjs \
  demo/message-center-spring/frontend/test/call-record-side-panel.test.mjs \
  demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordService.java \
  demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/callrecord/CallRecordServiceTest.java
```

Expected: `git diff --check` exit 0；差异只包含本计划定义的保存冲突修复，其他 dirty worktree 文件保持原样。

- [ ] **Step 4: 浏览器验收**

目标路径：电话仓库 -> 打开截图对应记录右侧详情 -> 编辑人工修订稿 -> 保存。

Expected: 保存按钮请求期间禁用；保存成功后退出编辑并新增修订历史；若真实并发修订发生，草稿保留并提示冲突。

当前内置浏览器发现结果为无可用实例。实施完成后重试一次 Browser；若仍不可用，在交付中明确记录浏览器交互未执行，不用构建结果替代。
