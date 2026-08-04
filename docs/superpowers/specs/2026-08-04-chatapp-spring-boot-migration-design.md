# ChatApp Stage 2：Spring Boot 迁移设计

## 目标

将原 Node.js demo 中 ChatApp/CAMS 子系统的发送、同步、webhook 能力迁移到 Spring Boot 架构，同时用 channel 包结构收敛各渠道代码。

## 渠道包结构

四个渠道统一收敛到 `channel/` 顶层包，渠道之间隔离、独立演进：

```
channel/
  chatapp/
    ChatAppSendService.java         # 发送（text/template/media）
    ChatAppMessageSyncService.java  # 消息同步逻辑
    ChatAppTemplateSyncService.java # 模板同步逻辑
    ChatAppSyncScheduler.java       # @Scheduled 入口
    ChatAppController.java          # /api/chatapp/* 端点
    SyncCursorEntity.java           # 游标实体
    SyncCursorMapper.java           # 游标 Mapper
  email/
    ...
  wecom/
    ...
  callrecord/
    ...
```

共享的 DTO、infrastructure、config 留在顶层原处，不进 channel。

## DB Schema（Flyway V6）

```sql
CREATE TABLE IF NOT EXISTS sync_cursors (
    id          BIGSERIAL PRIMARY KEY,
    cursor_key  VARCHAR(128) NOT NULL UNIQUE,  -- 'message_sync' | 'template_sync'
    cursor_val  VARCHAR(512),                   -- CAMS nextToken / pageNo
    updated_at  TIMESTAMP DEFAULT NOW()
);
```

用 DB cursor 替代原 demo 的 JSONL 文件持久化游标，重启不丢进度。

## 同步模型

@Scheduled 定时间隔替代 ScheduledExecutorService + 生命周期状态机：

```java
@Component
@ConditionalOnProperty(name = "app.chatapp.sync-enabled", havingValue = "true",
        matchIfMissing = true)
public class ChatAppSyncScheduler {

    @Scheduled(fixedDelay = 5000)
    public void syncMessages() { messageSyncService.runOnce(); }

    @Scheduled(fixedDelay = 300_000)
    public void syncTemplates() { templateSyncService.runOnce(); }
}
```

每个 @Scheduled 内部 try/catch，防止一次异常导致定时任务停止。

runOnce 流程：
1. 读 DB cursor_val
2. 调 CAMS SDK 拉取
3. 批量写入目标表
4. 更新 cursor_val
5. 返回 SyncResult { pages, fetched, saved, durationMs }

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chatapp/send/text` | 发送文本消息 |
| POST | `/api/chatapp/send/template` | 发送模板消息 |
| POST | `/api/chatapp/send/media` | 发送媒体消息（上传 OSS + 发送） |
| POST | `/api/chatapp/sync/messages` | 手动触发消息同步 |
| POST | `/api/chatapp/sync/templates` | 手动触发模板同步 |
| POST | `/api/chatapp/webhook` | CAMS 回调接收 |

## 核心类

### ChatAppSendService

```java
@Service
public class ChatAppSendService {
    public SendMessageResponse sendText(String to, String text);
    public SendMessageResponse sendTemplate(String to, String templateCode,
            String templateName, String languageCode);
    public SendMessageResponse sendMedia(String to, String mediaUrl, String mediaType);
}
```

sendMedia 流程：GetChatappUploadAuthorization → HMAC-SHA1 签名 OSS PUT → SendChatappMessage。

### ChatAppMessageSyncService

runOnce() → CAMS ListChatappMessage → 批量写入 message 表 → 更新 cursor。

### ChatAppTemplateSyncService

runOnce() → CAMS ListChatappTemplate → upsert template 表 → 更新 cursor。

### ChatAppController

```java
@RestController
@RequestMapping("/api/chatapp")
public class ChatAppController {
    @PostMapping("/send/text")       → sendService.sendText(...)
    @PostMapping("/send/template")   → sendService.sendTemplate(...)
    @PostMapping("/send/media")      → sendService.sendMedia(...)
    @PostMapping("/sync/messages")   → messageSyncService.runOnce()
    @PostMapping("/sync/templates")  → templateSyncService.runOnce()
    @PostMapping("/webhook")         → 解析 CAMS 回调，写入 message 表
}
```

## 依赖关系

```
ChatAppController
  ├── ChatAppSendService → Alibaba CAMS SDK
  └── ChatAppSyncScheduler
        ├── ChatAppMessageSyncService → CAMS SDK + SyncCursorMapper + MessageMapper
        └── ChatAppTemplateSyncService → CAMS SDK + SyncCursorMapper + TemplateMapper
```

AppConfig 中已有 `custSpaceId`、`chatappFrom`、`chatappTo`、`chatappChannelType` 注入到服务中。

## 前端约束

ThreadPage 中的 ChatApp tab（文本/模板发送）保持现有 UI 不变，仅底层 API 从 `/api/send` 迁移到 `/api/chatapp/send/*`。

## 与原 demo 的差异

| 项目 | 原 demo | Stage 2 |
|------|---------|---------|
| 游标存储 | JSONL 文件 | PostgreSQL sync_cursors 表 |
| 定时调度 | ScheduledExecutorService + 状态机 | @Scheduled + @ConditionalOnProperty |
| 模板存储 | templates.json 文件 + TemplateStore | PostgreSQL templates 表（已迁移） |
| 路由 | App.java if/else | @RequestMapping("/api/chatapp") |
| 依赖注入 | AppInjector.getXxx() | Spring @Service + 构造注入 |
