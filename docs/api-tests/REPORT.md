# 全量前端接口测试报告

> 生成命令：`python scripts/mc-api-full-sweep.py`　·　生成时间：2026-09-30 13:54:51
> 前端源：`demo/message-center-spring/frontend/src/api/endpoints.ts`（1629 行）
> 后端源：`demo/message-center-spring/backend/src/main/java`（45 个 controller 文件）

## 一、口径

三层账，任何一层单独看都有盲区：

| 层 | 怎么取 | 能抓什么 | 抓不到什么 |
|---|---|---|---|
| 前端声明面 | 解析 `endpoints.ts` 的 `client.<verb>(path)` | **写接口也能覆盖** | 服务端有没有 |
| 后端路由表 | 解析 `@RestController` 上所有 Mapping 注解 | 源码真值 | 跑着的进程是不是这份 |
| 活体 OPTIONS | 对每条路径发 `OPTIONS`，读状态码 + `Allow` | 进程陈旧 / 路径未注册 | 业务逻辑对不对 |

**判据**：`OPTIONS 404` = 路径未注册；`Allow` 不含声明动词 = 动词用错；只读实测 `5xx` = 真故障（`4xx` 是业务拒绝，说明路由是通的）。

「路径未注册」要再分三档 —— **三种的修法完全不同**，混在一起会把「配置没开」当成「接口坏了」：

| 现象 | 组合 | 结论 |
|---|---|---|
| 活体 404 | 源码**有** + 类被 `@Conditional` 门住 | 按配置未启用，**不是缺陷** |
| 活体 404 | 源码**有** + 未被门禁 | **进程跑的是旧 class**（改完没重启 / 发布没跟上） |
| 活体 404 | 源码**也没有** | **前端孤儿** —— 前端在调一个后端不存在的接口 |

## 二、总量

| 项 | 数 |
|---|---|
| 前端声明接口（去重后 路径×动词） | **190** |
| └ GET | 77 |
| └ POST | 91 |
| └ PUT / PATCH / DELETE | 14 / 8 / 9 |
| 后端路由（路径×动词） | **224** |
| 后端 controller 文件 | 45 |
| 活体探针实际发出 | 190 条 OPTIONS + 72 条 GET |
| **通过** | **158** |
| **有问题（需要修）** | **8** |
| 按部署配置未启用（非缺陷） | 33 |

**活体状态码分布**（`通过` 不等于 `全 200`）：

- `OPTIONS`（190 行）：200 × 156、404 × 34
- 只读实测（72 条）：200 × 26、400 × 9、404 × 34、410 × 1、500 × 2

> `OPTIONS 404` = 路径未注册（细分为「前端孤儿」与「配置未启用」）；只读 `4xx` = 业务 / 参数 / 权限拒绝（**说明路由是通的**，样本 id 是随机 UUID，查不到很正常）；只读 `5xx` = 真故障。

### 问题分布

| 类别 | 条数 | 含义 |
|---|---|---|
| `SOURCE_MISSING` | 2 | 后端源码里没有这条路由 —— 前端在调一个不存在的接口 |
| `VERB_MISMATCH` | 3 | 路径在，但动词不对 |
| `LIVE_UNREGISTERED` | 2 | 源码里也没有、活体也 404（与 `SOURCE_MISSING` 是同一件事的两面） |
| `LIVE_VERB_MISMATCH` | 3 | 活体 Allow 里没有声明动词 |
| `READ_5XX` | 3 | 只读接口实测 5xx |
| `LIVE_GATED` | 33 | 类上有 `@Conditional*` 门禁、当前部署未启用（**不是缺陷**） |

> 同一条接口可能同时命中多档（例如 `/api/v1/whatsapp/templates` 既是 `VERB_MISMATCH`、也被活体的 `Allow` 判为 `LIVE_VERB_MISMATCH`）—— 所以上表各档之和会**大于**「需要修」的条数，按**接口**看以「需要修 = 8 条」为准。

> **`LIVE_GATED` 不是缺陷。** 这批路由在后端源码里存在，但所属 controller 的类上有条件装配注解（`@ConditionalOnExpression`、`@ConditionalOnWeComEnabled`），当前部署不满足条件 ⇒ **进程启动时就没注册它们**，活体探针自然 404。判据是三条同时成立：源码有 + 类被门住 + 活体 404。
>
> 本轮命中的是**企微（WeCom）整面**：`@ConditionalOnWeComEnabled` + `@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")`，而 `WECOM_SUITE_ID` 为空 ⇒ 企微控制器、网关、配置全部不装配。这不是「接口坏了」，是「这套部署没开企微」。

## 三、逐个问题

### `SOURCE_MISSING` · 2 条

| 动词 | 路径 | 前端函数 | 源码动词 | 活体 |
|---|---|---|---|---|
| PUT | `/api/v1/whatsapp/templates/{templateCode}/remark` | `updateAdminTemplateRemark` | — | 404 |
| PUT | `/api/v1/whatsapp/templates/{templateCode}/send-permission` | `setAdminTemplateSendPermission` | — | 404 |

### `VERB_MISMATCH` · 3 条

| 动词 | 路径 | 前端函数 | 源码动词 | 活体 |
|---|---|---|---|---|
| POST | `/api/v1/whatsapp/templates` | `createAdminTemplate` | GET | 200 |
| PUT | `/api/v1/whatsapp/templates/{templateCode}` | `updateAdminTemplate` | GET | 200 |
| DELETE | `/api/v1/whatsapp/templates/{templateCode}` | `deleteAdminTemplate` | GET | 200 |

### `LIVE_UNREGISTERED` · 2 条

| 动词 | 路径 | 前端函数 | 源码动词 | 活体 |
|---|---|---|---|---|
| PUT | `/api/v1/whatsapp/templates/{templateCode}/remark` | `updateAdminTemplateRemark` | — | 404 |
| PUT | `/api/v1/whatsapp/templates/{templateCode}/send-permission` | `setAdminTemplateSendPermission` | — | 404 |

### `LIVE_VERB_MISMATCH` · 3 条

| 动词 | 路径 | 前端函数 | 源码动词 | 活体 |
|---|---|---|---|---|
| POST | `/api/v1/whatsapp/templates` | `createAdminTemplate` | GET | 200 |
| PUT | `/api/v1/whatsapp/templates/{templateCode}` | `updateAdminTemplate` | GET | 200 |
| DELETE | `/api/v1/whatsapp/templates/{templateCode}` | `deleteAdminTemplate` | GET | 200 |

### `READ_5XX` · 3 条

| 动词 | 路径 | 前端函数 | 源码动词 | 活体 |
|---|---|---|---|---|
| GET | `/api/media/{id}` | `fetchMediaUrl` | GET | 200 |
| GET | `/api/media/{id}` | `fetchMediaBlob` | GET | 200 |
| GET | `/api/media/{id}/download` | `downloadAttachment` | GET | 200 |

### `LIVE_GATED` · 33 条

| 动词 | 路径 | 前端函数 | 源码动词 | 活体 |
|---|---|---|---|---|
| POST | `/api/auth/wecom/exchange` | `exchangeWeComLogin` | POST | 404 |
| GET | `/api/account/wecom-binding` | `fetchWeComBinding` | DELETE,GET | 404 |
| POST | `/api/account/wecom-binding/exchange` | `exchangeWeComBinding` | POST | 404 |
| DELETE | `/api/account/wecom-binding` | `unbindWeCom` | DELETE,GET | 404 |
| POST | `/api/account/wecom-avatar/authorizations` | `createWeComAvatarAuthorization` | POST | 404 |
| GET | `/api/account/wecom-avatar/authorizations/{authorizationId}` | `fetchWeComAvatarAuthorization` | GET | 404 |
| POST | `/api/v1/wecom/conversation-view/bootstrap` | `bootstrapWeComViewer` | POST | 404 |
| GET | `/api/v1/wecom/js-sdk-config` | `fetchWeComJsSdkConfig` | GET | 404 |
| POST | `/api/v1/wecom/conversation-view/sessions` | `createWeComViewerSession` | POST | 404 |
| POST | `/api/v1/wecom/conversation-view/sessions` | `createWeComViewerTargetSession` | POST | 404 |
| GET | `/api/v1/wecom/conversation-view/sessions/{viewerSessionId}` | `fetchWeComViewerSession` | GET | 404 |
| POST | `/api/v1/wecom/conversation-view/events` | `recordWeComViewerEvent` | POST | 404 |
| GET | `/api/v1/wecom/installations` | `fetchWeComInstallations` | GET | 404 |
| GET | `/api/v1/wecom/installations/{authCorpId}/contact-events` | `listWeComContactEvents` | GET | 404 |
| POST | `/api/v1/wecom/installations/{authCorpId}/app-chats` | `createWeComAppChat` | POST | 404 |
| GET | `/api/v1/wecom/installations/{authCorpId}/app-chats/{chatId}` | `fetchWeComAppChat` | GET,PATCH | 404 |
| PATCH | `/api/v1/wecom/installations/{authCorpId}/app-chats/{chatId}` | `updateWeComAppChat` | GET,PATCH | 404 |
| POST | `/api/v1/wecom/installations/{authCorpId}/app-chats/{chatId}/messages` | `sendWeComAppChatMessage` | POST | 404 |
| GET | `/api/v1/wecom/installations/{authCorpId}/external-contacts` | `listWeComExternalContacts` | GET | 404 |
| GET | `/api/v1/wecom/installations/{authCorpId}/external-contacts/contact-links` | `listWeComExternalContactLinks` | GET | 404 |
| GET | `/api/v1/wecom/installations/{authCorpId}/external-contacts/{externalUserId}` | `getWeComExternalContact` | GET | 404 |
| POST | `/api/v1/wecom/installations/{authCorpId}/external-contacts:batchGet` | `batchGetWeComExternalContacts` | POST | 404 |
| PATCH | `/api/v1/wecom/installations/{authCorpId}/external-contacts/{externalUserId}/remark` | `updateWeComExternalContactRemark` | PATCH | 404 |
| POST | `/api/v1/wecom/installations/{authCorpId}/customer-groups:search` | `searchWeComCustomerGroups` | POST | 404 |
| GET | `/api/v1/wecom/installations/{authCorpId}/customer-groups/{chatId}` | `fetchWeComCustomerGroup` | GET | 404 |
| GET | `/api/v1/wecom/installations/{authCorpId}/directory/members/{userId}` | `fetchWeComDirectoryMember` | GET | 404 |
| GET | `/api/v1/wecom/installations/{authCorpId}/directory/members` | `listWeComDirectoryMembers` | GET | 404 |
| GET | `/api/v1/wecom/installations/{authCorpId}/directory/departments` | `listWeComDepartments` | GET | 404 |
| GET | `/api/v1/wecom/installations/{authCorpId}/directory/tags` | `listWeComTags` | GET | 404 |
| POST | `/api/v1/wecom/installations/{authCorpId}/directory/profile-sync` | `syncWeComDirectoryProfiles` | POST | 404 |
| GET | `/api/v1/wecom/installations/{authCorpId}/directory/tags/{tagId}` | `fetchWeComTag` | GET | 404 |
| POST | `/api/v1/wecom/groups/{sourceConversationId}/name-refresh` | `refreshWeComGroupName` | POST | 404 |
| POST | `/api/wecom/send` | `sendWeCom` | POST | 404 |

## 四、通过清单（前 40 条，全量见 `sweep.json`）

| 动词 | 路径 | 活体 | Allow | 只读实测 |
|---|---|---|---|---|
| POST | `/api/auth/login` | 200 | POST,OPTIONS | — |
| POST | `/api/auth/register` | 200 | POST,OPTIONS | — |
| GET | `/api/account/profile` | 200 | PATCH,GET,HEAD,OPTIONS | — |
| PATCH | `/api/account/profile` | 200 | PATCH,GET,HEAD,OPTIONS | — |
| POST | `/api/account/avatar` | 200 | POST,DELETE,OPTIONS | — |
| DELETE | `/api/account/avatar` | 200 | POST,DELETE,OPTIONS | — |
| GET | `/api/account/avatar/content` | 200 | GET,HEAD,OPTIONS | 404 |
| PUT | `/api/account/password` | 200 | PUT,OPTIONS | — |
| GET | `/api/admin/users` | 200 | GET,HEAD,OPTIONS | 200 |
| GET | `/api/admin/roles` | 200 | GET,HEAD,OPTIONS | 200 |
| PUT | `/api/admin/users/{userId}/roles` | 200 | PUT,OPTIONS | — |
| PUT | `/api/admin/users/{userId}/password` | 200 | PUT,OPTIONS | — |
| POST | `/api/auth/logout` | 200 | POST,OPTIONS | — |
| POST | `/api/conversations/preferences/pin` | 200 | POST,OPTIONS | — |
| POST | `/api/conversations/preferences/delete` | 200 | POST,OPTIONS | — |
| POST | `/api/conversations/preferences/restore` | 200 | POST,OPTIONS | — |
| POST | `/api/conversations/preferences/order` | 200 | POST,OPTIONS | — |
| GET | `/api/contacts` | 200 | GET,HEAD,OPTIONS | 200 |
| GET | `/api/channel-address-books/{channel}` | 200 | POST,GET,HEAD,OPTIONS | — |
| POST | `/api/channel-address-books/{channel}` | 200 | POST,GET,HEAD,OPTIONS | — |
| DELETE | `/api/channel-address-books/{channel}/{contactId}` | 200 | DELETE,OPTIONS | — |
| GET | `/api/conversations` | 200 | GET,HEAD,OPTIONS | 200 |
| GET | `/api/contacts/{id}` | 200 | GET,HEAD,OPTIONS | 400 |
| GET | `/api/contacts/{id}/memory` | 200 | GET,HEAD,OPTIONS | 404 |
| POST | `/api/contacts/{id}/mark-read` | 200 | POST,OPTIONS | — |
| POST | `/api/contacts/{id}/remark` | 200 | POST,OPTIONS | — |
| PUT | `/api/contacts/{id}/tags` | 200 | PUT,OPTIONS | — |
| POST | `/api/contacts/{id}/profile` | 200 | POST,OPTIONS | — |
| POST | `/api/contact-groups/merge` | 200 | POST,OPTIONS | — |
| POST | `/api/contact-groups/split` | 200 | POST,OPTIONS | — |
| GET | `/api/threads` | 200 | GET,HEAD,OPTIONS | 400 |
| GET | `/api/threads/wecom/contact/{contactId}` | 200 | GET,HEAD,OPTIONS | 200 |
| GET | `/api/threads/wecom/group/{sourceConversationId}` | 200 | GET,HEAD,OPTIONS | 400 |
| GET | `/api/v1/wecom/groups/{sourceConversationId}/topics` | 200 | GET,HEAD,OPTIONS | 200 |
| POST | `/api/v1/wecom/groups/{sourceConversationId}/topics/retry` | 200 | POST,OPTIONS | — |
| GET | `/api/messages/{id}` | 200 | GET,HEAD,OPTIONS | 404 |
| POST | `/api/send/email` | 200 | POST,OPTIONS | — |
| POST | `/api/send/email` | 200 | POST,OPTIONS | — |
| POST | `/api/chatapp/send/template` | 200 | POST,OPTIONS | — |
| POST | `/api/chatapp/send/text` | 200 | POST,OPTIONS | — |

…另有 118 条通过，明细见 `sweep.json`。

## 五、后端有、前端没调（信息性，不是问题）

共 **39** 条。它们是 webhook、回调、或以 `curl`/第三方调用的路由。

<details><summary>展开</summary>

- `GET /api`
- `GET /api/admin/chatapp/capabilities`
- `GET /api/contact-groups`
- `GET /api/events`
- `GET /api/v1/call-records/{}/audio`
- `GET /api/v1/contacts/{}/topics/attempts`
- `GET /api/v1/email/submissions/unknown`
- `GET /api/v1/wecom/app-callback`
- `GET /api/v1/wecom/authorization/callback`
- `GET /api/v1/wecom/message-summaries`
- `GET /api/v1/wecom/message-summaries/{}`
- `GET /api/wecom/callback`
- `GET /hook_path`
- `POST /api/account/wecom-binding/attempts`
- `POST /api/admin/chatapp/capabilities/add-number`
- `POST /api/admin/chatapp/capabilities/migration-review`
- `POST /api/admin/chatapp/capabilities/read-only`
- `POST /api/admin/chatapp/capabilities/send-code`
- `POST /api/admin/chatapp/capabilities/verify`
- `POST /api/admin/whatsapp/cams/{}/accounts/sync`
- `POST /api/auth/wecom/attempts`
- `POST /api/chatapp/sync/messages`
- `POST /api/chatapp/sync/messages/reconcile`
- `POST /api/chatapp/sync/templates`
- `POST /api/chatapp/webhook`
- `POST /api/email/send`
- `POST /api/email/sync`
- `POST /api/send/chatapp`
- `POST /api/v1/admin/call-records/contact-backfill`
- `POST /api/v1/email/messages`
- `POST /api/v1/webhooks/chatapp`
- `POST /api/v1/wecom/app-callback`
- `POST /api/v1/wecom/authorization/callback`
- `POST /api/v1/wecom/conversation-view/sync`
- `POST /api/v1/wecom/installations/{}/profile-backfill`
- `POST /api/v1/whatsapp/messages`
- `POST /api/wecom/callback`
- `POST /api/whatsapp/authorization/complete/{}/phone`
- `POST /hook_path`

</details>

## 六、没能静态解析的前端导出

这些不是接口，或是 fetch/别名，需人工确认（不影响上面的结论）：

- `createWeComAttempt` — 路径表达式没还原出来: path
- `fetchChatAppBroadcastTemplates` — 非 client.* 调用（fetch/SSE/别名）
- `sendAssistantMessage` — 非 client.* 调用（fetch/SSE/别名）

## 七、别名函数（转调另一个端点函数，不重复计数）

- `fetchChatAppBroadcastTemplates` → `fetchChatAppSendableTemplates`
- `sendAssistantMessage` → `streamAssistantMessage`

## 八、怎么复现

```bash
# 1) 静态对账（只读源码，不碰服务）
python scripts/mc-api-full-sweep.py --no-live

# 2) 全量（需要 8107 在跑；活体 OPTIONS 不触发任何业务方法）
python scripts/mc-api-full-sweep.py
```

活体探针**只用 OPTIONS**，不发明文 GET/POST 到写接口 —— `FrameworkServlet.doOptions` 只查 handler mapping，不会进入任何 `@RequestMapping` 方法。
