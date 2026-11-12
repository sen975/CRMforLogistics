# 企业微信 Spring 审计自清理迁移验收记录

**日期：** 2026-08-18

**范围：** `demo/message-center-spring` 的 `wecom_viewer_audit`、`wecom_authorization_audit`

## 已验证

- Flyway 真实 PostgreSQL 容器应用 21 个迁移，企业微信审计清理迁移为 `V21__wecom_audit_retention.sql`；ChatApp 已占用的 V20 未修改。
- Mapper 集成测试真实运行 Testcontainers PostgreSQL 17.5，验证过期 viewer 删除、批大小、已闭合授权阶段删除、开放 `event_id + attempt` 全阶段保护、legacy 行处理和状态表 upsert。
- WeCom 专项命令：

  ```bash
  mvn -q -Dapi.version=1.44 -Dtest='*WeCom*Test' test
  ```

  结果：157 tests，0 errors，0 failures，0 skipped。Docker 29 使用 `-Dapi.version=1.44`。

- Retention service 测试验证两个 stream 的批次循环、最大批次数、未满批成功状态、锁竞争、失败状态、运行状态和配置边界。
- Scheduler/模块隔离测试验证 startup gate、异常边界、`app.wecom-enabled` 和 suite ID 条件。
- 配置门禁确认四项 `WECOM_AUDIT_*` 在 `application.yml` 和 `.env.example` 中存在；敏感 YAML 扫描无命中。
- `mvn -q -DskipTests package` 退出码 0。
- `git diff --check` 无输出。
- 暂存区保持为空，未执行 `git add`、`commit`、`restore --staged`，未修改 ChatApp 的 V20 迁移或其他用户 WIP。

## 全量回归残余

执行：

```bash
mvn -q -Dapi.version=1.44 test
```

Surefire 报告共 675 tests，0 failures，1 error，0 skipped。唯一错误为既有 ChatApp WIP：`ChatAppPeerReconciliationMapperSqlTest` 通过 `JdbcTemplate` 传入 `java.time.Instant` 时 PostgreSQL 无法推断 SQL 类型；错误发生在 `messages` fixture 插入，不属于本次企业微信审计清理范围，本轮未修改。

## 运维边界

- 默认保留 7 天、每小时触发、每批 500 行、单轮最多 32 批；配置范围由 retention service 校验。
- PostgreSQL advisory lock 按每个短事务保护清理；`budget_remaining` 由满批判断，下轮继续处理。
- 开放授权 attempt 以 `event_id + attempt` 为单位完整保留；只有 legacy `event_id IS NULL` 行按普通过期记录处理。
- 清理失败写入 `failed` 状态并输出稳定错误码/结构化 warning，不阻断 viewer/chatdata；授权 required 审计写入和 fail-closed 语义不变。
- 本模块不清理通用 `audit_logs`，不处理业务消息、stdout/stderr 或 Docker 日志。

## 未完成实机项

真实企业微信扫码、授权回调、公钥注册和 chatdata 上游同步仍需部署环境凭据与真实网络验收；本次仅验证 Spring 本地代码和真实 PostgreSQL SQL 行为。
