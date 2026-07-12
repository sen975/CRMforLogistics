# Java 邮件收发 Demo

这个 demo 用 Java + Jakarta Mail 实现 SMTP 发信、IMAP 收信、已发送邮件同步、新邮件轮询提示，以及一个本地邮件会话页面。代码保持独立，后续可以把核心类迁入 Spring Boot。

## 功能

- `verify`：验证 SMTP 和 IMAP 登录。
- `send`：发送测试邮件。
- `folders`：列出 IMAP 文件夹名称，用来确认收件箱/已发送文件夹该怎么配置。
- `receive`：手动拉取 IMAP 收件箱和已发送文件夹最近邮件，写入 `data/inbox.jsonl`。
- `watch`：轮询监听收件箱和已发送文件夹的新邮件，终端提示，并写入 `data/inbox.jsonl`。
- `web`：启动本地邮件会话页面。
- `watch-web`：同时启动页面和邮件轮询监听。

## 配置

复制配置：

```powershell
copy .env.example .env
```

修改 `.env` 里的 SMTP/IMAP 主机、端口、账号和授权码。

关键配置：

```env
INBOX_FOLDER=INBOX
SENT_FOLDER=已发送
RECEIVE_LIMIT=10
WATCH_POLL_SECONDS=30
DATA_DIR=data
WEB_PORT=8088
```

说明：

- `MAIL_TO` 只用于 `send` 命令的默认收件人，不限制收信范围。
- `receive/watch/watch-web` 会读取 `IMAP_USERNAME + INBOX_FOLDER` 的来信。
- `receive/watch/watch-web` 也会读取 `IMAP_USERNAME + SENT_FOLDER` 的发出邮件，并按 `direction=out` 写入会话。
- 页面会话按邮件真实时间 `sentDate` 排序，解析不了时才回退到本地写入时间 `storedAt`。
- 不同邮箱的已发送文件夹名不一样，常见值包括 `Sent`、`Sent Messages`、`Sent Items`、`已发送`、`已发送邮件`。
- 如果不想同步已发送邮件，可以把 `SENT_FOLDER` 留空。
- 可以先运行 `.
mail-demo.ps1 folders` 查看 IMAP 服务器真实文件夹名。

## PowerShell 命令

```powershell
.\mail-demo.ps1 verify
.\mail-demo.ps1 folders
.\mail-demo.ps1 send
.\mail-demo.ps1 send -To receiver@example.com -Subject "CRM logistics mail test"
.\mail-demo.ps1 receive
.\mail-demo.ps1 watch
.\mail-demo.ps1 web
.\mail-demo.ps1 watch-web
```

页面默认访问：

```text
http://localhost:8088
```

## cmd 命令

如果 PowerShell 执行策略不允许运行 `.ps1`，可以用：

```cmd
mail-demo.cmd verify
mail-demo.cmd folders
mail-demo.cmd send
mail-demo.cmd receive
mail-demo.cmd watch-web
```

## 直接使用 Maven

PowerShell 里不要写：

```powershell
mvn -q compile exec:java -Dexec.args="send"
```

PowerShell 会把它拆坏，Maven 会报 `Unknown lifecycle phase ".args=send"`。

应该写成：

```powershell
mvn -q compile exec:java "-Dexec.args=send"
```

更推荐直接使用 `mail-demo.ps1`。

## 存储

邮件记录写入：

```text
data/inbox.jsonl
```

每条记录包含：

- `direction`：`in` 表示来信，`out` 表示发出邮件。
- `contactEmail`
- `contactName`
- `from`
- `to`
- `subject`
- `sentDate`
- `summary`
- `bodyText`
- `messageId`

这个文件只是 demo 本地存储。接入 Spring Boot 后建议替换成数据库表。

## 后续接入 Spring Boot

- `MailConfig` 改成 `@ConfigurationProperties(prefix = "mail.demo")`。
- `SmtpMailer` 改成 `@Service`。
- `ImapMailbox` 改成 `@Service`。
- `InboxStore` 替换为数据库 repository。
- `InboxWebServer` 替换为 Spring MVC Controller / REST API。
- `watch` 入口用 `ApplicationRunner`、`@Scheduled` 或后台任务启动。

当前 demo 不接业务客户、权限、AI 草稿和数据库线程，只验证邮件基础收发、同步和页面展示能力。

## 联系人合并

页面左侧联系人支持拖拽合并：把一个联系人拖到另一个联系人上，会把源联系人邮箱合并到目标联系人邮箱下。合并后左侧只显示目标联系人，消息列表会读取组内所有邮箱的邮件，并继续按邮件真实时间排序。

点击联系人头像可以查看该联系人当前合并了哪些邮箱。面板中 `Primary` 表示主邮箱，其他邮箱可以点击 `Split` 拆分出来。拆分不会删除邮件，只会移除本地联系人合并关系。

合并关系写入：
```text
data/contact-groups.jsonl
```

邮件原始记录仍保存在 `data/inbox.jsonl`，不会因为合并联系人而改写历史邮件。
