# 企业微信专区最小会话索引同步设计

## 1. 真源地位

本文定义企业微信会话展示组件获取真实 `msgid + secret_key` 的唯一主线，补充：

- `2026-07-27-wecom-conversation-viewer-design.md`
- `2026-07-28-wecom-delegated-authorization-installation-design.md`

上述文档中的扫码登录、代开发授权安装、短时 viewer token、一次性 viewer session、权限、限流、审计和前端 `ww-open-message` 接线继续有效。与本文冲突的“由本地模拟 `sync_msg` 或人工 JSONL 注入提供真实消息”描述失效；模拟入口只能用于本地测试，不能作为正式数据来源。

官方约束以以下文档为准：

- 会话展示组件：`https://developer.work.weixin.qq.com/document/path/100049`
- 获取会话记录：`https://developer.work.weixin.qq.com/document/path/100023`
- 应用同步调用专区程序：`https://developer.work.weixin.qq.com/document/path/100020`
- 专区程序开发指引：`https://developer.work.weixin.qq.com/document/path/100056`

## 2. 当前问题

消息中心已经能够完成企业微信浏览器扫码登录、代开发安装选择、JS-SDK 初始化、viewer 权限校验和官方组件挂载，但服务器尚无真实会话消息引用。会话展示组件不会自行查询消息列表；前端必须向组件传入真实 `message-id` 和解密后的 `secret-key`。

旧 `/api/wecom/sync_msg`、消息注入、`gettoken` 和 demo webhook 运行时路径已移除，模拟 helper 只在隔离测试中使用。8067 旧 Python 回调程序没有专区会话存档数据，也不属于本链路。没有真实 `msgid + secret_key` 时，组件只能验证外壳和错误态，不能验收真实消息收发。

## 3. 本轮目标

- 新建独立的“会话展示索引同步”能力，不复用摘要或分析能力。
- 交付一个最小 Java 17、`linux/amd64` 专区镜像，只调用 `sync_msg` 并返回官方允许的消息索引字段。
- 8107 使用现有代开发授权安装获取应用 access token，调用 `sync_call_program`。
- 8107 解密 `encrypted_secret_key`，把 viewer 所需最小引用写入现有 `WECOM_DATA_FILE`。
- 员工点击企业微信 Viewer 时有界同步最新索引，同步完成后继续创建现有一次性 viewer session。
- 真实验收一条员工发给外部联系人的消息和一条外部联系人回复的消息，均由官方组件显示原文。

## 4. 非目标

- 不做 Topic、摘要、客户画像、关键词、情感、推荐话术或任何 AI 分析。
- 不把原始会话正文写入 8107、JSONL、数据库、日志、审计或前端状态。
- 不让普通前端 JavaScript 读取、抓取或复制 `ww-open-message` 内部正文。
- 不引入 PostgreSQL、队列、定时 worker、多实例协调或长期后台同步。
- 不停止、改造或依赖 8067 旧 Python 回调程序。
- 不在首版支持群聊身份投影；只闭合一名员工与一名外部联系人的一对一消息。
- 不新增公开同步 API；同步由现有 viewer session 创建链路编排。
- 不为下一阶段 Topic 预留临时输入输出字段。Topic 需要独立设计和能力合同。

## 5. 推荐主线

```text
Browser
  -> POST /api/v1/wecom/conversation-view/sessions
  -> 8107 WeCom chatdata sync orchestration
  -> POST /cgi-bin/chatdata/sync_call_program
  -> 已关联的专区程序与能力
  -> 专区镜像内部 SDK sync_msg
  -> msg_list + encrypted_secret_key + next_cursor
  -> 8107 RSA private-key decrypt
  -> bounded WECOM_DATA_FILE projection
  -> existing WeComViewerService
  -> ww-open-message(message-id, secret-key)
```

专区镜像是企业微信专区内的数据入口，不是消息中心服务的替代品。8107 仍然拥有授权企业选择、员工身份、联系人权限、cursor、viewer session 和审计语义。

## 6. Owner 与组件边界

| 概念 | 唯一 owner | 禁止成为 owner 的层 |
|---|---|---|
| 专区 `sync_msg` 调用与官方字段投影 | 最小专区程序 | 8107 controller、前端、8067 |
| 代开发安装与应用 access token | 现有 `WeComAuthorizationStore` / authorization gateway | 镜像、前端、静态 AgentID/Secret |
| `sync_call_program` HTTP 适配、响应上界和错误映射 | 新 `WeComChatDataGateway` | `WeComViewerService`、前端 |
| cursor、消息引用去重与文件发布 | 新 `WeComChatDataStore` | controller、镜像、前端 |
| 点击 viewer 前的有界同步编排 | 新 `WeComChatDataSyncService` | `WeComViewerService`、HTTP gateway |
| viewer token、session、员工 owner 校验、消息数量上限 | 现有 `WeComViewerService` | 同步服务、镜像、前端 |
| 官方消息正文展示 | 企业微信 `ww-open-message` | 8107、JSONL、前端全局状态 |

`WeComViewerService` 不直接拥有外部 HTTP 调用、RSA 解密或 cursor。session 创建路由先调用同步服务，成功后再委托现有 viewer 服务创建快照。

## 7. 专区程序能力合同

新建独立能力“会话展示索引同步”，能力 ID 固定为 `conversation_viewer_sync`。摘要或分析能力不属于本链路，也不得被镜像接受。`program_id` 使用该专区程序审核通过后的实际 ID，8107 的 `WECOM_CHATDATA_ABILITY_ID` 必须配置为同一个固定值。

### 7.1 输入协议

```json
{
  "cursor": "string，可选，首次不传",
  "limit": "number，1 到 200",
  "token": "string，可选，后续接入专区会话回调时透传"
}
```

镜像固定使用 `mode=0`。本轮不接受 `begin_time`、`page_id`、任意 SDK 方法名或任意输出字段，避免把镜像变成通用代理。

### 7.2 输出协议

```json
{
  "has_more": 0,
  "next_cursor": "string",
  "msg_list": [
    {
      "msgid": "string",
      "sender": {
        "type": 1,
        "id": "string"
      },
      "chatid": "string，可选",
      "receiver_list": [
        {
          "type": 2,
          "id": "string"
        }
      ],
      "send_time": 0,
      "msgtype": 1,
      "service_encrypt_info": {
        "encrypted_secret_key": "string",
        "public_key_ver": 1
      }
    }
  ]
}
```

镜像只投影 `sync_msg` 的上述字段，不返回正文、调试输入、模型结果、SDK token 或额外元数据。输出数组最多 200 条；字符串、接收人数量和总响应体都必须有上界。

## 8. 最小镜像

新增独立工程：

```text
demo/wecom-chatdata-zone-program/
```

约束：

- Java 17。
- `linux/amd64`、glibc 兼容镜像。
- 单一启动命令 `/app/start`，不依赖后台启动参数。
- `/app/start` 使用 `exec java` 启动专区程序主类，正确传递退出码和终止信号。
- 不包含消息中心、数据库客户端、Topic 模型、Web 服务器或无关依赖。
- 不把 Suite Secret、permanent code、RSA 私钥、access token 或企业身份打入镜像。
- 运行时不持久化消息，不创建无界缓存，不打印 SDK 请求或响应正文。
- 按官方 Java 1.4.0 示例要求，先 `docker create` 再通过 `docker export` 导出企业微信后台可上传的 rootfs `.tar` 文件；该文件不是 `docker save` 镜像归档。

实现必须基于企业微信后台“专区程序示例/查看使用文档”提供的官方 Java 示例或 SDK 包。若该文件只能登录后台下载，用户需把原始包放入实施计划指定的本地输入目录；在取得官方包之前允许完成 8107 fake-gateway 测试，但禁止伪造 SDK 或声称镜像可以提交审核。

## 9. 8107 同步数据流

### 9.1 触发

员工登录并点击企业微信 Viewer 后：

1. 前端显示“正在同步企业微信会话”。
2. session 创建路由根据 viewer token 解析同一 `installationId + version + wecomUserId`。
3. `WeComChatDataSyncService` 对该授权企业获取单进程同步锁。
4. 同步服务读取与 `suiteId + authCorpId + programId + abilityId` 绑定的 cursor。
5. 每页调用一次 `sync_call_program`，每页最多 200 条，最多 5 页，总计最多 1000 条。
6. 每页完整校验、解密和投影成功后，先发布消息引用快照，再原子更新 cursor。
7. `has_more=0` 后委托现有 `WeComViewerService` 创建 session。
8. 若 5 页后仍有更多数据，返回 `WECOM_CHATDATA_SYNC_INCOMPLETE`，不创建 viewer session；用户再次点击后从已保存 cursor 继续。

同步总超时为 15 秒。消息中心其他渠道不等待、不重试该同步。

### 9.2 首次和增量

官方 `sync_msg` 首次不传 cursor，从最近 5 天最早消息开始升序返回。后续必须使用上次成功页的 `next_cursor` 增量拉取。`begin_time` 只用于人工恢复，不进入本轮运行路径。

没有回调 token 时允许进行低频点击同步，并依赖本项目 viewer session 限流和企业维度单飞锁。专区回调 token 与事件驱动同步属于后续独立实现。

### 9.3 崩溃一致性

单页处理遵循：

```text
validate whole page
  -> decrypt whole page
  -> normalize whole page
  -> write temp message snapshot
  -> atomic replace WECOM_DATA_FILE
  -> write temp cursor snapshot
  -> atomic replace cursor file
```

消息快照先于 cursor。若进程在两次替换之间崩溃，下一次会重复拉取该页，但 store 按 `msgid + userid + external_userid` 幂等 upsert，不会丢消息或无界追加重复行。任意消息校验或解密失败时整页拒绝，消息文件和 cursor 都不更新。

## 10. 消息引用投影

本轮只接受一对一员工与外部联系人消息：

- `sender.type=1` 且恰有一个 `receiver.type=2`：`userid=sender.id`，`external_userid=receiver.id`。
- `sender.type=2` 且恰有一个 `receiver.type=1`：`external_userid=sender.id`，`userid=receiver.id`。
- 机器人、群聊、多个员工、多个外部联系人或无法唯一映射的消息不进入 viewer 文件，只增加不含身份明文和密钥的结构化跳过计数。

每条 viewer JSONL 引用包含：

```json
{
  "msgid": "...",
  "secret_key": "...",
  "external_userid": "...",
  "userid": "...",
  "send_time": 0,
  "msgtype": "..."
}
```

`secret_key` 只保存在权限受限的本地 viewer 数据文件中，不进入普通消息正文存储、日志、审计或 HTTP 错误。文件默认最多保留按 `send_time` 排序后的 5000 条引用，并受 8 MiB 硬上限保护；达到条数上限时丢弃最早引用，超过字节上限且无法安全发布时失败关闭。

## 11. RSA 公钥与私钥

企业微信必须先配置用于 `service_encrypt_info.encrypted_secret_key` 的 RSA 公钥，否则不会形成可用会话存档引用。

8107 使用单一当前私钥文件和显式公钥版本：

```text
WECOM_CHATDATA_PRIVATE_KEY_FILE
WECOM_CHATDATA_PUBLIC_KEY_VERSION
```

规则：

- 私钥文件必须是绝对路径、权限受限且不位于发布包或 Git 中。
- `public_key_ver` 必须与配置完全一致；不匹配时失败关闭。
- RSA 解密结果必须满足长度和字符上界，再作为 `secret_key` 使用。
- 私钥、密文和解密结果不得进入异常消息、日志、审计、测试快照或前端响应。
- 公钥轮换需要独立设计多版本 keyring；首版不隐式尝试多把私钥。

## 12. 配置

新增：

```env
WECOM_CHATDATA_PROGRAM_ID=prog...
WECOM_CHATDATA_ABILITY_ID=...
WECOM_CHATDATA_PRIVATE_KEY_FILE=/absolute/path/to/wecom_chatdata_private_key.pem
WECOM_CHATDATA_PUBLIC_KEY_VERSION=1
WECOM_CHATDATA_CURSOR_FILE=/absolute/path/to/wecom-chatdata-cursor.json
WECOM_CHATDATA_SYNC_LIMIT=200
WECOM_CHATDATA_SYNC_MAX_PAGES=5
WECOM_CHATDATA_SYNC_TIMEOUT_SECONDS=15
WECOM_CHATDATA_STORE_MAX_MESSAGES=5000
WECOM_CHATDATA_STORE_MAX_BYTES=8388608
```

`program_id`、`ability_id` 不是密钥，可以通过环境配置下传。私钥只能通过文件下传。相对 cursor 和消息路径必须通过现有 `DATA_DIR` 解析为稳定绝对路径，不能依赖守护进程工作目录。

## 13. HTTP 与资源边界

`WeComChatDataGateway`：

- 复用当前安装记录获取对应企业的应用 access token。
- 只调用官方 `chatdata/sync_call_program` 路径。
- 连接、写入、读取和总调用均有超时。
- 请求体、响应体和 `response_data` 各自有 1 MiB 上限。
- 只接受 HTTP 2xx、外层 `errcode=0`、合法 JSON 字符串 `response_data` 和完全匹配的内部合同。
- 不自动重试非幂等未知失败；用户再次点击触发下一次有界同步。

同一授权企业只允许一个同步任务。并发点击返回 `WECOM_CHATDATA_SYNC_BUSY`，不排无界等待队列。

## 14. 错误合同

新增结构化错误：

| code | HTTP | 语义 |
|---|---:|---|
| `WECOM_CHATDATA_NOT_CONFIGURED` | 503 | program、ability、私钥、版本或 cursor 配置缺失 |
| `WECOM_CHATDATA_SYNC_BUSY` | 429 | 同一企业已有同步任务 |
| `WECOM_CHATDATA_TIMEOUT` | 504 | 专区调用超过总超时 |
| `WECOM_CHATDATA_PROGRAM_ERROR` | 502 | HTTP、外层 errcode、专区程序或响应合同失败 |
| `WECOM_CHATDATA_SYNC_INCOMPLETE` | 409 | 本次达到 5 页上限但仍有更多数据 |
| `WECOM_CHATDATA_KEY_VERSION_MISMATCH` | 500 | 返回公钥版本与服务器配置不一致 |
| `WECOM_CHATDATA_DECRYPT_FAILED` | 500 | RSA 密钥不可用或解密结果非法 |
| `WECOM_CHATDATA_STORE_FAILED` | 500 | 消息引用或 cursor 无法安全发布 |

错误响应不得包含 access token、回调 token、密钥、密文、完整官方响应、用户 ID 或外部联系人 ID。没有当前员工相关消息是正常空态，不算同步错误。

同步开始、成功、跳过计数、页上限、忙、超时、程序错误、密钥错误和存储错误进入现有 viewer 审计。审计写入失败继续 fail closed。

## 15. 前端行为

- 继续复用现有企业微信 tab、Viewer 按钮、SDK 懒加载、10 秒 SDK 超时、toast 和错误容器。
- 点击后先显示同步加载态，再创建和读取 viewer session。
- `WECOM_CHATDATA_SYNC_INCOMPLETE` 显示“仍有历史消息待同步，请再次点击继续”。
- `WECOM_CHATDATA_SYNC_BUSY` 显示“企业微信会话正在同步，请稍后重试”。
- 同步成功但无当前联系人消息时显示明确空态。
- 同步失败不得回退到模拟消息、旧 8067 数据或其他企业安装记录。
- 官方组件展示的正文不复制到 Vue 状态、DOM 外层、localStorage、sessionStorage、日志或审计。

## 16. 测试计划

### 16.1 专区程序

- 输入缺失、cursor 上界、limit 下界和上界、未知字段拒绝。
- SDK 成功、SDK 错误、超时、畸形 JSON 和超大响应。
- 官方字段最小投影，正文和额外字段不出现在输出。
- 容器以 `/app/start` 启动、非零错误正确退出、终止信号可传递。
- 构建 `linux/amd64` 镜像，架构检查、`docker export` 和使用 `docker import` 的 tar 完整性检查通过。

### 16.2 8107 单元与集成

- `sync_call_program` 请求绑定正确 program、ability 和安装 access token。
- 外层与内部响应解析、1 MiB 上限、15 秒总超时和错误映射。
- RSA 成功、错误版本、错误私钥、畸形密文和敏感错误脱敏。
- 首次空 cursor、增量 cursor、最多 5 页、`has_more` 和升序消息。
- 消息快照先于 cursor；两次发布之间崩溃后重拉幂等。
- 一对一收发身份投影；群聊、机器人和歧义接收人安全跳过。
- 同企业单飞、并发忙、viewer 用户 owner 校验和审计失败关闭。
- `WECOM_DATA_FILE` 条数与字节上限、按消息引用去重和最近消息保留。
- session 创建先同步，成功后继续复用现有最近 10 条和硬上限 20。

### 16.3 回归门禁

在 JDK 17 下执行：

```text
mvn -q test
mvn -q test-compile
mvn -q -Dexec.mainClass=com.crmforlogistics.messagecenter.UnifiedMessageStoreTest -Dexec.classpathScope=test exec:java
mvn -q -DskipTests package
node contracts/openapi/message-center-v1.test.mjs
git diff --check
```

不新增公开路由，OpenAPI operation 数量保持当前代码真源的 27；只补现有 viewer session 的结构化错误合同和必要说明。

## 17. 真实验收

1. 从企业微信后台取得官方专区 Java 示例或 SDK 包，构建并导出最小 `.tar` 镜像。
2. 在当前专区程序中把临时分析能力改为“会话展示索引同步”，填写本文输入输出协议。
3. 上传镜像，启动命令填写 `/app/start`，不填启动参数，提交并通过企业微信审核。
4. 把最终 `program_id` 配置到 8107，并设置 `WECOM_CHATDATA_ABILITY_ID=conversation_viewer_sync`。
5. 设置会话存档 RSA 公钥，把匹配私钥和版本配置到 8107。
6. 使用已经授权且开启会话存档的员工，与一个外部联系人互发消息。
7. 浏览器扫码登录同一员工，选择对应联系人并点击 Viewer。
8. 验证员工发出的消息和外部联系人回复均由 `ww-open-message` 显示。
9. 验证加载态、无消息、同步忙、页上限、密钥版本错误、专区错误和组件错误态。
10. 保存桌面和移动 viewport 的真实截图；字符串探针、模拟 JSONL 和组件外壳不能替代真实验收。

## 18. 完成边界与外部阻塞

本地实现完成的定义是：专区镜像工程、可复现镜像构建命令、8107 同步链路、测试和部署文档全部通过本地门禁。真实消息展示还依赖企业微信外部状态：

- 官方 Java 示例或 SDK 包可取得；
- 专区程序镜像审核通过并关联当前代开发应用；
- 授权企业开通数据与智能专区和会话存档权限；
- 已设置正确 RSA 公钥；
- 最近 5 天内存在授权员工与外部联系人的真实消息。

任一外部条件缺失时必须返回明确未就绪原因，不能使用模拟消息伪装真实闭环。
