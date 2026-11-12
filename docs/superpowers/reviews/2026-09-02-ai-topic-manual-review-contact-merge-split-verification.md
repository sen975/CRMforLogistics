# AI Topic 手动整理与联系人合并拆分验收记录

日期：2026-09-02

## 交付结果

- 新增独立 `REVIEW_PENDING` 状态与联系人合并、拆分、人工整理来源元数据；`DISCARDED` 未重新进入当前状态合同。
- 普通 Topic、待确定 Topic 与仓库 Topic 分区；待确定项展示操作来源、原 Topic 标题、来源数和时间范围。
- 手动整理支持按联系方式和时间范围查询，逐条勾选、全选当前结果、AI 预览、调整目标 Topic 和整批应用；单批上限 200 条。
- Topic 合并改为 AI 融合：确认后生成新 Topic ID，来源原位迁移，旧 Topic 进入 `ARCHIVED`。
- 联系人合并把来源 `READY` Topic 转为目标联系人下的待确定 Topic；拆分只恢复对应联系方式的来源到新联系人待确定区；`WECOM_GROUP` 不参与个人联系人迁移。
- 混合来源的 `READY` Topic 在拆走来源后，由 AI Topic owner 使用全部剩余来源重新生成标题和概要；AI 响应遗漏来源、输入超限或版本冲突时，联系人拆分事务整体回滚。`STORED` 仍保持仓库快照语义，不自动转为活跃 Topic。

## 2026-09-03 修正记录

手动整理来源查询的 `WECOM_SUMMARY_NOT_COMPLETED` 提示现在只针对当前选中的企业微信联系方式。联系人同时拥有企业微信和邮件/电话联系方式时，邮件或电话查询不会因企业微信图片摘要失败记录而被拦截。

本轮专项验证：`mvn -Dtest=AiTopicManualReviewServiceTest test`，3 个用例通过。后端全量测试中 952 个非容器用例通过，10 个 Testcontainers 集成用例因本机 Docker 不可用失败，7 个用例跳过。

## 自动化门禁

后端：

```bash
cd demo/message-center-spring/backend
mvn -q -Dtest='*AiTopic*Test,*ContactGroup*Test,ContactServiceBeanInstantiationTest' test
mvn -q -DskipTests package
```

结果：专项门禁运行 97 个用例通过、1 个 PostgreSQL Testcontainers 用例因 Docker 不可用跳过；打包退出码 0，生成 `target/message-center.jar`。测试中的 AI provider 失败日志来自异常分级/截断用例，不是门禁失败。

后端全量门禁也已执行。直接运行 `mvn -q test` 时，Docker Desktop daemon 返回无效信息，10 个 Testcontainers 集成类失败；排除这 10 个明确依赖 Docker 的类后，其余后端全量测试退出码为 0。全量结果没有断言失败，Docker 集成类不记为通过。

前端：

```bash
cd demo/message-center-spring/frontend
npm run test -- --run
npm run build
```

结果：源码合同 29/29 通过；Vitest 50 个测试文件、252/252 用例通过；TypeScript 与 Vite 生产构建成功。

制品结构：

```bash
unzip -t demo/message-center-spring/frontend-dist-20260902-ai-topic-manual-review-r2.zip
unzip -l demo/message-center-spring/backend/target/message-center.jar
```

结果：前端压缩包无错误且根目录为 `dist/`；Jar 包含 `V40__ai_topic_review_pending.sql`、`V41__ai_topic_manual_review.sql`、`V42__ai_topic_fusion_previews.sql` 及新的 Topic review/fusion 类。

## 浏览器验收

- 环境：Browser 插件，`http://127.0.0.1:5173/conversations/contact/d526bde8-6521-4a9d-8cc4-8ae28629a780`，桌面视口 1512 x 805。
- 页面身份、非空页面、无框架错误覆盖层均通过。
- 联系人右栏显示普通 Topic、独立“待确定 Topic”和“手动整理 Topic”；展开后显示联系方式、开始时间、结束时间与查询按钮。
- Console 无应用错误；只有 React Router v7 future flag 开发期警告。
- 已采集桌面截图；未写入仓库。

当前 `8107` 监听的是此前启动的旧后端进程，不包含本次最终 Jar。真实来源查询在该旧进程环境中停留在加载状态，因此没有将查询响应、AI 预览、应用或拆分后实时重算写成已通过的浏览器证据。移动视口也未完成，Browser 当前运行面没有暴露可用的视口调整方法。这些项目需要在替换新 Jar、Flyway 成功迁移后补验。

## 环境限制

`AiTopicReviewMapperIntegrationTest` 使用真实 PostgreSQL Testcontainers 动态执行 `listSources`、`listSourcesByIds` 和 `listTopicSources`，并配置 `disabledWithoutDocker = true`。本机 Docker Desktop daemon 当前不可用，该测试被跳过；另外 10 个依赖 Docker 的全量集成类也无法完成。本轮不能据此声称动态 SQL 已在真实 PostgreSQL 上通过。部署启动时必须以 Flyway 启动成功、服务器 API 查询和联系人拆分实测补齐该门禁。

## 发布制品

- 后端：`demo/message-center-spring/backend/target/message-center.jar`
  - SHA-256：`e33997b1e0b6dae93e788cbe0e1fe26edd8e9e25ee89c638ef38c8a922a7a526`
- 前端：`demo/message-center-spring/frontend-dist-20260902-ai-topic-manual-review-r2.zip`
  - SHA-256：`1efc126a241b777776a0160ef8cc63799c60f2a7607e7c7e2b6b2d05380b9e60`

## 服务器停止条件

- 新 Jar 启动日志出现 Flyway checksum、V40/V41/V42 迁移或 SQL 错误时，停止部署并保留旧 Jar，不继续切前端。
- 登录后来源查询返回 5xx、持续加载，或联系人合并/拆分后 Topic 混入普通时间轴时，不执行人工 apply 操作，先保留 traceId 和服务日志。
- 前端实际入口未引用新 hash、页面存在运行时错误或待确定区与普通 Topic 混排时，恢复备份的 `dist`。

## 部署命令

以下命令假设两个制品已上传到：

- `/data/project_testing/source/message-center-spring/backend/target/message-center.jar`
- `/data/project_testing/source/message-center-spring/frontend-dist-20260902-ai-topic-manual-review-r2.zip`

先部署后端并确认 Flyway V40-V42 成功：

```bash
set -euo pipefail

BASE=/data/project_testing/source/message-center-spring
UPLOAD_JAR="$BASE/backend/target/message-center.jar"
LIVE_JAR=/data/project_testing/source/message-center.jar
BACKUP_DIR=/data/project_testing/backup
BACKUP_JAR="$BACKUP_DIR/message-center-$(date +%Y%m%d-%H%M%S).jar"
EXPECTED_JAR_SHA=e33997b1e0b6dae93e788cbe0e1fe26edd8e9e25ee89c638ef38c8a922a7a526

test -f "$UPLOAD_JAR"
test "$(sha256sum "$UPLOAD_JAR" | awk '{print $1}')" = "$EXPECTED_JAR_SHA"
mkdir -p "$BACKUP_DIR"
test ! -e "$BACKUP_JAR"
cp -p "$LIVE_JAR" "$BACKUP_JAR"
install -m 0644 "$UPLOAD_JAR" "$LIVE_JAR"
systemctl restart spring_message-center.service
systemctl is-active --quiet spring_message-center.service
journalctl -u spring_message-center.service -n 200 --no-pager | grep -E 'Flyway|V40|V41|V42|Started'

echo "后端部署完成，旧 Jar：$BACKUP_JAR"
```

后端确认正常后部署前端：

```bash
set -euo pipefail

BASE=/data/project_testing/source/message-center-spring
ZIP="$BASE/frontend-dist-20260902-ai-topic-manual-review-r2.zip"
TARGET="$BASE/frontend/dist"
BACKUP_DIR=/data/project_testing/backup
BACKUP="$BACKUP_DIR/frontend-dist-$(date +%Y%m%d-%H%M%S)"
STAGE=$(mktemp -d "$BASE/.frontend-stage.XXXXXX")
EXPECTED_ZIP_SHA=1efc126a241b777776a0160ef8cc63799c60f2a7607e7c7e2b6b2d05380b9e60

test -f "$ZIP"
test "$(sha256sum "$ZIP" | awk '{print $1}')" = "$EXPECTED_ZIP_SHA"
unzip -t "$ZIP"
unzip -q "$ZIP" -d "$STAGE"
test -f "$STAGE/dist/index.html"
nginx -t

mkdir -p "$BACKUP"
if [ -d "$TARGET" ]; then
  mv "$TARGET" "$BACKUP/dist"
fi
mv "$STAGE/dist" "$TARGET"
rmdir "$STAGE"
/etc/init.d/nginx reload

echo "前端部署完成，旧版本：$BACKUP"
grep -oE '/assets/[^"]+\.js' "$TARGET/index.html"
```
