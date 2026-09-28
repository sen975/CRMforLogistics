# Flyway V84 / V89 迁移漂移 · 取证与恢复

**日期：** 2026-09-23
**对象：** dev 库 `message_center`（容器 `crm-logistics-message-center-postgres-1`）
**触发：** `flyway validate` 在 dev 库上拒启 —— V84 本地文件缺失、V89 checksum 不匹配
**状态：** V84（选项 A）与 V89 复原**均已执行并验收**，见 §8。未改动任何数据库内容。

---

## 0. 结论摘要

1. **V84 原始 SQL 文件在这台机器上已不可恢复**（12 条取证路线全部穷尽，见 §2）；但**语义已被三方证据唯一确定**（见 §4）。
2. **V89 的原始内容已被精确复原** —— 得到一份 checksum **逐位吻合**库记录的版本（`-1472033022`），**等价于找回原文件**（见 §3）。
3. **V84 按选项 A 处置**：重建文件 + 用 CRC32 对齐 checksum，**未动库**（见 §6.3、§8）。
4. **没有 schema 漂移被掩盖**：V84 只有一根按设计该删的孤儿列；V89 的漂移是一个**活故障**（静默审计丢失，见 §5）。
5. **不要 `flyway repair`**：它会删除 V84 的 history 行（丢失审计），并静默接受 V89 的新 checksum（那正是漂移所在）。
6. **验收判据已达成**：真 flyway-core 10.20.1 对 dev 库 `validate` 返回 **OK**（忽略 pending 时）；不忽略时**只剩 pending V91–V96**，无任何 checksum/缺失类错误。

---

## 1. 现状：validate 失败的两个独立原因

| # | 迁移 | 库记录 | 本地文件 | 失败类型 |
|---|---|---|---|---|
| 1 | `V84__chatapp_message_sync_cursor.sql` | checksum `1556840289`，`installed_on 2026-09-22 09:37:27` | **不存在** | applied migration not resolved locally |
| 2 | `V89__whatsapp_cams_callback_apply_claim.sql` | checksum `-1472033022`，`installed_on 2026-09-22 11:27:00` | 存在，但算出 `-1850384056` | migration checksum mismatch |

两者是**不同性质**的问题：V84 是"文件丢了"，V89 是"文件被改了"。处置方式也必须分开。

---

## 2. 取证过程：为什么说原文件不可恢复

已穷尽的 12 条路线，全部为**空**：

| # | 路线 | 结果 |
|---|---|---|
| 1 | git 全历史（含所有分支） | V84 / V89 **从未被提交** |
| 2 | git 未跟踪状态 | V79/V80/V81/V88/V89/V91–V96 全是 `??`（untracked） |
| 3 | git dangling blob | 738 个，**无一个是 SQL 文件**（逐个解包检查） |
| 4 | git stash | 空 |
| 5 | IntelliJ LocalHistory | **只存了文件名，没有内容**（`ALTER TABLE` / `ADD COLUMN` 全 0 命中）|
| 6 | VS Code User/History | 无相关条目 |
| 7 | `/tmp` 副本（`mc-*`、13 个 `mc-target-*`） | 快照全为 09-20（早于 V84）或当前版本 |
| 8 | `target/classes/db/migration` | 只有当前版本 |
| 9 | `~/.m2/repository` | 无 `com/crmforlogistics` 产物（从未 `mvn install`）|
| 10 | Docker 镜像 | 最新 `crm-logistics-message-center-backend:latest` 为 **07-23**，远早于 09-22 |
| 11 | 另两个 git worktree | `vtree` 是 09-16 快照（迁移只到 V79）；主 worktree 无 `message-center-spring` |
| 12 | Spotlight 文件名/内容检索 + Time Machine | 文件名搜为空；**Time Machine 未配置任何备份目的地** |

**其中第 5 条值得单独记一条教训**：IntelliJ LocalHistory 的 `changes.storageData`（2.4 MB）看起来很有希望，实测**只保存了文件路径与名字**，内容 0 命中。不要把它当成"编辑器的 git"。

---

## 3. 发现一：V89 的原始内容已精确复原

### 3.1 方法：先拿到权威 checksum 算法

⚠️ **本文初版把算法描述错了，此处已按 flyway-core 10.20.1 的实际字节码更正。**

`org.flywaydb.core.internal.resolver.ChecksumCalculator#calculateChecksumForResource`（`javap -c` 反编译结果）：

```java
CRC32 crc32 = new CRC32();
BufferedReader br = new BufferedReader(resource.read(), 4096);
String line = br.readLine();
while (line != null) {
    line = BomFilter.FilterBomFromString(line);   // 只去掉行首的 \uFEFF
    crc32.update(line.getBytes(UTF_8));
    line = br.readLine();
}
return (int) crc32.getValue();
```

⇒ **它把"每一行去掉行尾换行符"后首尾拼成一整段，对这一段做一次 CRC32**（同一个 `CRC32` 对象跨行 `update`），**不是逐行 XOR**。两个直接推论：

- **增删空行不改变 checksum**（空行贡献 0 字节）—— 所以对齐全可以靠"追加一行注释"做到；
- 行序敏感、行内任何字节都敏感。

用库里账本校准本地实现（Python `zlib.crc32(data, crc)` 与 Java `CRC32.update` 语义一致）：

```
89 条已应用记录 → 87 条逐位相符，唯一不符的两条正是 V84（文件缺失）与 V89（被改）
```

⇒ 校准同时给出一个额外事实：**除 V89 外，所有现存迁移文件都还是"执行时那一份"**，没有被后续编辑过。

### 3.2 反推：改动范围被唯一确定

以当前 V89 文件为基线，枚举"移除哪些段落"的候选，比对目标 checksum：

| 候选改动 | 算出的 checksum | 命中 |
|---|---|---|
| 仅去掉 `audit_result` 约束里的 `SUBMISSION_UNKNOWN` | 1404502578 | ✗ |
| 仅去掉 `apply_status` 约束里的 `SUBMISSION_UNKNOWN` | -416294189 | ✗ |
| 两处都收缩 | -994803975 | ✗ |
| **删掉整段 `whatsapp_cams_callback_audits` 约束变更** | **-1472033022** | **★ 精确命中** |

⇒ **V89 执行时根本没有 audit 约束那一段**，它是**执行后被追加**的。

### 3.3 三方交叉验证

| 证据源 | 结论 | 一致 |
|---|---|---|
| checksum 反推 | 删掉 audit 段 ⇒ `-1472033022` | — |
| schema 实测 | `ck_..._apply_status` 含 5 值（含 `SUBMISSION_UNKNOWN`）；`ck_..._audit_result` **只有 2 值** | ✅ |
| 时间线 | `installed_on = 11:27:00`，文件 mtime = `11:30` ⇒ 执行后 3 分钟被改 | ✅ |

三条独立证据指向同一结论，可以当作定论。

### 3.4 复原产物

`/tmp/flyway-restore/V89__whatsapp_cams_callback_apply_claim.sql`（605 字节，漂移版 888 字节）

```sql
ALTER TABLE whatsapp_cams_callback_configs
    ADD COLUMN IF NOT EXISTS apply_token uuid,
    ADD COLUMN IF NOT EXISTS apply_started_at timestamptz;

ALTER TABLE whatsapp_cams_callback_configs
    DROP CONSTRAINT IF EXISTS ck_whatsapp_cams_callback_apply_status;

ALTER TABLE whatsapp_cams_callback_configs
    ADD CONSTRAINT ck_whatsapp_cams_callback_apply_status
    CHECK (last_apply_status IN ('NEVER_APPLIED', 'APPLYING', 'SUCCEEDED', 'FAILED', 'SUBMISSION_UNKNOWN'));

CREATE INDEX IF NOT EXISTS ix_whatsapp_cams_callback_apply_claim
    ON whatsapp_cams_callback_configs(id, version, apply_token);
```

**checksum = -1472033022（★ 与库记录一致，已由 flyway-core 本尊复算确认）。** 该文件已落到迁移目录，见 §8。

---

## 4. 发现二：V84 的真身与根因

### 4.1 曾用名线索

IntelliJ LocalHistory 虽无内容，但留下了两个文件名：

- `V84__channel_account_message_sync_cursor.sql`（曾用名）
- `V84__chatapp_message_sync_cursor.sql`（库记录的名字）

### 4.2 决定性证据：设计文档

`docs/superpowers/plans/2026-09-21-chatapp-review-remediation.md:81-84`：

> - Verify: `V2__channel_conversation_message.sql` already provides `channel_sync_cursors`; its existing `cursor_timestamp` plus encoded `cursor_value` must represent the CAMS `(sendTime,messageId)` watermark, so no migration is expected for this task.
> - **Remove before implementation: unshipped temporary `V84__chatapp_message_sync_cursor.sql`** and the `channel_accounts.message_last_synced_at` field/path; the existing `channel_sync_cursors` table is the sole owner of message polling watermarks.

⇒ **V84 不是建表迁移**，`channel_sync_cursors` 由 `V2__channel_conversation_message.sql` 创建。V84 是**给 `channel_accounts` 加 `message_last_synced_at` 的临时方案**，计划评审后决定废弃它、统一到 `channel_sync_cursors`。

### 4.3 数据库侧验证（孤儿列仍在）

```
channel_accounts 的 sync 相关列：
  last_synced_at            ← V2 / V77 有来源
  message_last_synced_at    ← 无任何迁移负责  ★
  template_last_synced_at   ← V88 有来源
  sync_status               ← 有来源
  template_sync_status      ← V88 有来源
```

- 全库 `message_last_synced_at` **只出现在这一处**
- 代码里 `message_last_synced_at` / `messageLastSyncedAt` **0 命中**（字段与代码路径已按计划清除）
- **没有任何现存迁移提到它** ⇒ 它是一根**无人负责的孤儿列**

### 4.4 V84 的重建件（已对齐 checksum）

```sql
ALTER TABLE channel_accounts
    ADD COLUMN IF NOT EXISTS message_last_synced_at timestamptz;
```

（`IF NOT EXISTS` 与两行缩进风格参照同期 V88 / V89 的写法；`alter` 在两行前后各留一行注释头，末尾是对齐行。）

**注意**：重建件的内容本身**算不出**目标 checksum（`-330831051` ≠ `1556840289`），因为原件可能带注释头或多条语句。60 个"风格变体"均未命中后**不再盲试**，改用 §6.3 的对齐手段 —— 这也是选项 A 的定义。文件里明确写明了它是重建件、非原件。

---

## 5. 漂移评估：到底有没有"实际 schema 漂移"

**有，但只在 V89，且可精确定位。**

| 迁移 | 库中的实际效果 | 漂移 |
|---|---|---|
| V89 | `apply_token` / `apply_started_at` 列 ✅、`apply_status` 约束 5 值 ✅、`ix_..._apply_claim` 索引 ✅ | **audit_result 约束仍是 2 值** |
| V84 | `message_last_synced_at` 列 ✅（V84 唯一效果） | 无漂移；但该列**按设计应当被删除** |

### 5.1 V89 的漂移是一个活故障

当前代码 `WhatsAppCallbackConfigService` 在 4 处调用 `auditSafely(...)` 时传入 `"SUBMISSION_UNKNOWN"` 作为结果值（`87-88`、`105-106`、`134-135`、`149-150` 行）。而 `auditSafely` 内部：

```java
private void auditSafely(...) {
    try {
        audit(...);
    } catch (RuntimeException error) {
        LOG.error("callback audit write failed ...", error);
    }
}
```

`ck_whatsapp_cams_callback_audit_result` 只允许 `SUCCEEDED` / `FAILED` ⇒ 写入 `SUBMISSION_UNKNOWN` 会**违反 CHECK 约束**。

- **不会**导致接口 500（异常被 catch）
- **会**导致**审计记录静默丢失**，只留一条 ERROR 日志

⇒ 这是"provider 超时 / 版本冲突"这类**最需要审计**的场景下，审计反而断链。某人显然发现了这一点，于是去改 V89 文件 —— 这个修复**永远不会生效**，因为 V89 已应用。

### 5.2 契约测试为什么没拦住

`WhatsAppCallbackSchemaContractTest.applyClaimMigrationAddsExpiringClaimAndUnknownOutcomeContracts` 是**纯文件读取测试**（`Files.readString` + `contains`，无 `@SpringBootTest` / 无 Testcontainers），它断言 V89 **文件文本**包含 `ck_whatsapp_cams_callback_audit_result`。

⇒ 它验证的是"文件里写了什么"，**不是"库里生效了什么"**。文件被改后测试照样绿。这类测试无法发现"已应用迁移被编辑"的漂移。

> ⚠️ **V89 复原后这条测试会红**（已取证）：`WhatsAppCallbackSchemaContractTest.java:46` 断言 V89 文件含 `ck_whatsapp_cams_callback_audit_result`，而复原版 **0 命中**（漂移版 2 命中）。也就是说**这条断言编码的正是漂移后的内容** —— 它是照"被篡改的文件"写出来的，所以永远发现不了篡改。
>
> 修法不是删掉断言，而是把它改读 §6.2 第 2 步的 **V97**（audit 约束真正该在的地方）。V89 里其余断言（`apply_token` / `apply_started_at` / `'applying'` / `'submission_unknown'` / `ix_..._apply_claim`）在复原版中**仍然成立**，无需改动。

---

## 6. 恢复方案

### 6.1 总流程

```
第 1 步  V89 复原（零风险）              ← ✅ 已执行（第一轮，§8）
第 2 步  V97 前向迁移：修 audit 约束      ← ✅ 已执行（第二轮，§9）
第 3 步  V98 前向迁移：清理孤儿列         ← ✅ 已执行（第二轮，§9）
第 4 步  V84 文件与 checksum 对齐         ← ✅ 已执行（选项 A）
第 5 步  更新契约测试的断言归属           ← ✅ 已执行（第二轮，§9）
第 6 步  flyway validate 验收             ← ✅ 已达成（§8.4 / §9.3）
```

### 6.2 第 2、3、5 步（✅ 已执行，见 §9）

**第 2 步 — 新建 V97（修 audit 约束，前向）** ⇒ 已落盘为
`V97__whatsapp_cams_callback_audit_result_submission_unknown.sql`

```sql
ALTER TABLE whatsapp_cams_callback_audits
    DROP CONSTRAINT IF EXISTS ck_whatsapp_cams_callback_audit_result;

ALTER TABLE whatsapp_cams_callback_audits
    ADD CONSTRAINT ck_whatsapp_cams_callback_audit_result
    CHECK (result IN ('SUCCEEDED', 'FAILED', 'SUBMISSION_UNKNOWN'));
```

内容取自被误加进 V89 的那一段 —— **这是它本来就该在的位置**。

**第 3 步 — 新建 V98（清理孤儿列，落实 09-21 计划的设计意图）** ⇒ 已落盘为
`V98__channel_accounts_drop_orphan_message_sync_cursor.sql`

```sql
ALTER TABLE channel_accounts
    DROP COLUMN IF EXISTS message_last_synced_at;
```

> V97 / V98 都是**新迁移**，会在下次启动（或显式 migrate）时**真实写库**，与已 pending 的 V93–V96 一起执行。本轮只落盘文件 + 跑 validate，**没有触发 migrate**（§9.3）。

### 6.3 第 4 步：V84 的处置（已选 A）

| | 做法 | 优点 | 代价 |
|---|---|---|---|
| **A ✅ 已采用** | 恢复重建文件 + 用 CRC32 填充对齐 checksum | 迁移账本完整（V84 记录保留）；`validate` 全绿；门禁强度不变；**不动库** | 文件内容非逐字节原件（语义已由 §4 三方确证） |
| B | 恢复重建文件 + 一条定向修正 | 简单 | 需 `UPDATE flyway_schema_history SET checksum=... WHERE version='84'`；动了 history 表 |
| C | 不恢复文件，配置 `ignoreMissingMigrations=true` | 零改动 | **全局降低门禁强度**：今后任何迁移文件丢失都不再报错 |

**A 案的技术实现。** 已知目标 checksum `1556840289`，且 §3.1 已经把算法钉死为"拼接后整体一次 CRC32"。于是问题变成：

> 找一行注释 L，使 `crc32_continue(crc_pre, L)` 等于目标值。

用 CRC 的两个恒等式（已数值自检 2000 轮）做 meet-in-the-middle 解出 L 的 9 个十六进制字符：

```
Eq.1  g(c, X) = g(c, 0^m) ^ F(X) ^ F(0^m)              （定长消息 + 定长 init 上的 GF(2) 仿射性）
      F(X1||X2) = F(X1||0^m2) ^ F(0^m1||X2) ^ F(0^m)   （同上，用于拆分搜索空间）
```

其中 `g(c,·) = crc32_continue(c, ·)`、`F(·) = crc32(·)`（init=0）。左侧 4 位、右侧 5 位十六进制各 65536 / 1048576 个候选，期望命中 ~16 个，取第一个即可：

```
-- flyway-checksum-alignment: 2243fe6b7
```

**该行是纯填充，不含任何语义**；文件头也写明了"改一个字节就会重新报 mismatch"。

**不推荐 B 与 C 的理由**：B 把"内容不一致"这件事藏进库表（不可版本控制、不可 diff）；C 用全局开关换局部问题，会掩盖将来真正的漂移。

### 6.4 验收判据

`flyway validate` 全绿。结果见 §8.4。

---

## 7. 未验证 / 风险（截至本次）

- **V84 重建内容为语义等价、非逐字节原件**。依据是三处独立证据（计划文档、孤儿列存在、曾用名），但没有原件可做逐字节比对。已对齐的只是 checksum，不是"证明内容等于原件"。
- **V84 的原始文件是否还含其他语句未完全排除**。已核对 `channel_accounts` 全部 sync 相关列，仅 `message_last_synced_at` 无迁移来源；但不能排除它包含无副作用的语句（如注释、`IF NOT EXISTS` 包裹的重复定义）。**这不会造成 schema 漂移**（多出的语句在设计上无害），但意味着"重建件 ≠ 原件"这一点的置信度不是 100%。
- **V97 落盘 ≠ 已生效**：库里 `ck_..._audit_result` 仍只有 2 值 ⇒ §5.1 的静默审计丢失**仍在发生**，直到下一次应用启动（或显式 migrate）真正执行 V97。第二轮**刻意没有跑 migrate**。
- ~~当前有 1 条测试是红的~~ → **已修**：`WhatsAppCallbackSchemaContractTest` 的断言已按 §9.2 搬迁，**7/7 通过**。
- **孤儿列 `message_last_synced_at` 仍在库里**，直到 V98 被真正执行。
- **V84 文件头那句"94 个迁移文件里没有任何一个提到它"，在 V98 落盘后不再是字面事实**（V98 正是提到它的那一个）。该句描述的是**重建时刻**的取证状态而非永久断言；V84 一个字节都不能改（会弄脏 checksum），故在此说明，以免后来者误判为矛盾。
- **V97 / V98 尚未在干净库上完整重放过**。已确认的是「对既有 dev 库 validate 无冲突」，未确认的是「全新库从 V1 顺序执行到 V98 全部成功」。理论风险低（两者都是幂等 DDL，且依赖的对象 V79 / V2 都在更早的版本），但**未实测**。
- **`installed_on` 时区**：查出的时间为 `2026-09-22 09:37:27` / `11:27:00`，按库默认时区读取，与文件 mtime 比对时假定同时区。

---

## 8. 执行记录（2026-09-23 11:1x–11:3x）

### 8.1 改动清单

| 文件 | 动作 | 大小 | checksum（flyway-core 复算） |
|---|---|---|---|
| `backend/src/main/resources/db/migration/V84__chatapp_message_sync_cursor.sql` | **新建**（重建件 + 对齐行） | 1591 B | `1556840289` ✅ = 库记录 |
| `backend/src/main/resources/db/migration/V89__whatsapp_cams_callback_apply_claim.sql` | **复原**（888 B → 605 B） | 605 B | `-1472033022` ✅ = 库记录 |
| 漂移版 V89 | 备份到 `/tmp/flyway-restore/V89.drifted.bak.sql` | 888 B | `-1850384056` |

> `target/classes/db/migration/` 下的同名副本由 IDE 构建自动同步（11:31），内容与 src 一致。

### 8.2 工具链（可复用）

| 文件 | 作用 |
|---|---|
| `/tmp/flyway-restore/flyway_ck.py` | checksum 复现（`ck` / `ckdir` / `diff` / `align`），**与 flyway-core 本尊 94 个文件逐个一致** |
| `/tmp/flyway-restore/FwCk.java` | 权威 oracle：直接调 `ChecksumCalculator.calculate(new StringResource(...))` |
| `/tmp/flyway-restore/FwValidate.java` | 对 dev 库跑真 `validate()`（**只读**，不写 history），支持 `ignore-pending` |
| `/tmp/flyway-restore/cp.txt` | FwValidate 的 classpath（flyway-core + flyway-database-postgresql + pg 驱动 + jackson + slf4j-api）|

> 坑：`FileSystemResource(Location, ...)` 在很深的绝对路径下会抛 `StringIndexOutOfBoundsException`（`Location.getPathRelativeToThis`），所以 oracle 走 `StringResource` 传入文件内容。

### 8.3 关键判据：改动前后

**改动前**（V84 未落盘）：

```
VALIDATE: FAILED
Detected applied migration not resolved locally: 84.
Migration checksum mismatch for migration version 89
-> Applied to database : -1472033022
-> Resolved locally    : -1850384056
Detected resolved migration not applied to database: 91. … 96.
```

**改动后**（两文件就位，不忽略 pending）：

```
VALIDATE: FAILED
Detected resolved migration not applied to database: 91. … 96.     ← 仅此一类
```

⇒ **V84 的 "not resolved locally" 与 V89 的 "checksum mismatch" 双双消失**，剩余全部是 pending（尚未执行的新迁移，不是漂移）。

**改动后（`ignore-pending`）**：

```
VALIDATE: OK（无任何校验错误）
V84  state=SUCCESS
V89  state=SUCCESS
```

### 8.4 其它验收

| 项 | 命令 | 结果 |
|---|---|---|
| 全账本对平 | `flyway_ck.py diff` | `0 条不符 / 共 89 条已应用`；仅 6 条 PENDING（V91–V96）|
| 未写库 | 改动前后各导一次 `flyway_schema_history` 三列比对 | 89 行逐条一致 ✅ |
| 孤儿列未被顺手删除 | `information_schema.columns` | 仍存在（1 行）✅ |
| Python ≡ 真引擎 | 同目录 94 个文件交叉比对（§9.3 在 97 个文件上复跑，仍逐个一致） | 94/94 一致 ✅ |

### 8.5 副作用：一条契约测试转红（预期内）

`WhatsAppCallbackSchemaContractTest.java:46` 断言 **V89 文件文本**含 `ck_whatsapp_cams_callback_audit_result`：

```
复原版 V89 命中数 = 0        ← 现在是红的
漂移版 V89 命中数 = 2        ← 之前"绿"的来源
```

**这条断言本身就是在为漂移背书**：它规定"V89 必须包含 audit 约束"，而事实是 V89 执行时并不包含它。它不是没拦住漂移，而是**把漂移写成了契约**。

处理办法（§6.2 第 5 步）：该断言改读 V97；V89 的其余断言在复原版中仍成立。第二轮已执行，见 §9.2。

---

## 9. 执行记录（第二轮：V97 / V98 / 断言搬迁）

### 9.1 改动清单

| 文件 | 状态 | 说明 |
|---|---|---|
| `db/migration/V97__whatsapp_cams_callback_audit_result_submission_unknown.sql` | **新建**（约 1.0 KB） | `DROP`/`ADD CONSTRAINT ck_..._audit_result`，第三值 `SUBMISSION_UNKNOWN`；幂等 |
| `db/migration/V98__channel_accounts_drop_orphan_message_sync_cursor.sql` | **新建**（约 0.9 KB） | `DROP COLUMN IF EXISTS message_last_synced_at`；幂等 |
| `src/test/.../mapper/WhatsAppCallbackSchemaContractTest.java` | **修改** | 原 4 条 → 7 条（见 §9.2） |

**未改动**：V84、V89、V79、任何库表、任何 `src/main/java` 代码。

### 9.2 契约测试的搬迁（4 → 7 条）

| 测试方法 | 变化 | 理由 |
|---|---|---|
| `applyClaimMigrationAddsExpiringClaimAndUnknownOutcomeContracts` | **删**掉 `.contains("ck_whatsapp_cams_callback_audit_result")` | 该断言错把漂移写成契约（§5.2 / §8.5）。`'submission_unknown'` 仍保留 —— 它匹配的是 V89 里 `apply_status` 那一段，复原版仍有 |
| `applyClaimMigrationAsExecutedDoesNotCarryTheAuditResultConstraint` | **新增** | 用 `doesNotContain` 把"复原后的形态"钉住：谁把那段 ALTER 塞回 V89，validate 会报 mismatch，这条测试先一步说清原因 |
| `auditResultConstraintAcceptsUnknownOutcomeInForwardMigration` | **新增** | 断言落在 **V97**：修复只有写进新迁移才会作用到已存在的库 |
| `orphanMessageSyncCursorColumnIsDroppedInForwardMigration` | **新增** | 保护 V84 的重建件不被"顺手删列"（那会重新弄脏 checksum），并锁住 V98 的职责 |

**结果：`Tests run: 7, Failures: 0, Errors: 0`（BUILD SUCCESS，1.7 s）。**

### 9.3 验收

| 判据 | 结果 |
|---|---|
| 真 Flyway `validate`（不过滤） | `V84`/`V89` 无 mismatch、无 not-resolved；**只剩 pending 91–98** |
| 真 Flyway `validate ignore-pending` | **`VALIDATE: OK（无任何校验错误）`** |
| `V84` / `V89` info 状态 | 均 `state=SUCCESS`，`resolvedChecksum` = `1556840289` / `-1472033022` |
| **未写库** | 全程只创建文件 + 跑只读 validate，**没有执行 migrate**。改后实测：`flyway_schema_history` 仍是 **89 行 / max(version)=90**；孤儿列 `message_last_synced_at` **仍在**（count=1）；`ck_..._audit_result` **仍是两值**（V97 尚未生效）|
| 测试 | `WhatsAppCallbackSchemaContractTest` 7/7 通过 |
| 工具链复跑 | 目录扩到 **97 个文件**后，Python 实现与真 flyway-core **仍逐个一致（97/97）**；V97 = `-1575834406`、V98 = `-321026879` |

> 附加修正：辅助工具 `FwValidate.java` 把 `MigrationInfo.getInstalledOn()` 打成了 `appliedChecksum=`，本轮一并改为 `installedOn=`（纯标签错误，数值一直是对的）。

---

## 附录：产物位置

| 文件 | 说明 | checksum |
|---|---|---|
| `backend/src/main/resources/db/migration/V84__chatapp_message_sync_cursor.sql` | **已就位**：重建件 + CRC32 对齐行 | `1556840289` ★ |
| `backend/src/main/resources/db/migration/V89__whatsapp_cams_callback_apply_claim.sql` | **已就位**：精确复原 | `-1472033022` ★ |
| `backend/src/main/resources/db/migration/V97__whatsapp_cams_callback_audit_result_submission_unknown.sql` | **新建（第二轮）**：前向补 audit 约束第三值 | `-1575834406`（未执行） |
| `backend/src/main/resources/db/migration/V98__channel_accounts_drop_orphan_message_sync_cursor.sql` | **新建（第二轮）**：前向删孤儿列 | `-321026879`（未执行） |
| `backend/src/test/.../mapper/WhatsAppCallbackSchemaContractTest.java` | **已修改（第二轮）**：4 → 7 条，7/7 通过 | — |
| `/tmp/flyway-restore/V89.drifted.bak.sql` | 漂移版 V89 备份（888 B） | `-1850384056` |
| `/tmp/flyway-restore/flyway_ck.py` / `FwCk.java` / `FwValidate.java` | 校验 / oracle / 验收三件套；已沉淀为 skill `flyway-checksum-forensics` | — |
| 本文 | 取证、方法、执行记录 | — |
