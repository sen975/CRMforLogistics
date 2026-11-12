# 渠道注册表实施计划（越界改造第 ③ 步）

> 状态：**已执行（2026-09-20）**。设计、实际改动与验证证据见下文；未闭合项见第六节。
> 背景真源：`docs/代码板块地图.md` 第四节（改造前记为「不在基线内、但同样需要处理」）；
> 前置步骤：`plans/2026-09-20-chatapp-outbox-relocation.md`（② 归位，已完成）。

## 一、目标与判据

**目标**：消掉「新增一个渠道必须回头修改核心服务」这个根因。

**改造前，渠道知识散在 `ChannelAccountService` 的五处**（原代码证据）：

| # | 位置 | 写死了什么 |
|---|---|---|
| 1 | `CREDENTIAL_KEYS`（静态 Map） | 每个渠道允许的凭证字段 |
| 2 | `sync(ownerId, id)` 的 `switch (channelType)` | 同步分派；`default -> throw IllegalArgumentException` |
| 3 | `normalizeIdentifier(channelType, identifier)` | 邮箱转小写 / 其他去空格括号连字符 |
| 4 | `isWhatsAppChannel`、`assertCompatibleScope`、`bindProviderScope` | 写死的 `"chatapp"` 判断 |
| 5 | `dto/request/CreateChannelAccountRequest` 的 `@Pattern` | 渠道白名单 `chatapp\|whatsapp\|email` |

第 5 处在 DTO 里，容易被漏掉 —— 它同样让「新增渠道」必须改代码。

**为什么它不在架构门禁基线里**：`service.channel` 是渠道编排层，依赖 `channel` 包本身合法，
这不是依赖方向违规，而是实现方式问题。所以本次改动**不涉及基线**（实测基线保持 40 条不变）。

## 二、设计

一个 SPI + 一个注册表 + 每渠道一个实现。

```java
public interface ChannelType {
    String key();                                   // 落库值
    default Set<String> aliases() { return Set.of(); }   // 如 whatsapp → chatapp
    Set<String> credentialFields();
    default Set<String> secretFields() { return Set.of(); }
    String normalizeIdentifier(String identifier);
    default void assertBindableFromSettings() { }    // 默认放行
    default void assertScope(Map<String, String> credentials) { }
    default void bindScope(ChannelAccountEntity account) { }
    Object syncAccount(UUID ownerId, UUID accountId) throws Exception;
}
```

`ChannelTypeRegistry` 注入 `List<ChannelType>`，构造时按 `key()` 与 `aliases()` 建不可变索引：

- 未注册的名字 → `CHANNEL_ACCOUNT_TYPE_UNSUPPORTED`（400）。**核心不再有 `switch` 或 `default -> throw`**。
- 两个渠道抢同一个 key/别名 → **应用启动失败**。静默覆盖会让一个渠道凭空消失，比启动失败难查。
- `secretFields()` 取所有渠道的**并集**给凭证打码。改造前是核心里写死的 `SECRET_KEYS`；
  改成并集后，新渠道只要声明自己的敏感字段，就不会出现密钥被明文回显的漏口。

**为什么 `assert*` 钩子抛异常而不是返回标志位**：错误码归渠道自己拥有
（如 `WHATSAPP_ONBOARDING_REQUIRED`），核心因此不需要认识任何具体渠道的错误码。

**为什么同步也放进同一个接口**：如果再拆一个 `ChannelSync` 接口，一个渠道就要注册两处 —— 耦合更重。
当前只有 `ChannelAccountService` 一个消费者，合并更简单。等 email/wecom 真要进 outbox 时再说。

## 三、改动清单

### A. 新增 4 个文件

| 文件 | 内容 |
|---|---|
| `service/channel/ChannelType.java` | SPI，含实现约定与「新增渠道怎么做」的说明 |
| `service/channel/ChannelTypeRegistry.java` | 建索引 / 别名解析 / 冲突快速失败 / 敏感字段并集 |
| `service/channel/EmailChannelType.java` | `email`：12 个凭证字段、2 个敏感字段、标识转小写、`EmailSyncService.receiveLatest` |
| `service/channel/ChatAppChannelType.java` | `chatapp`（别名 `whatsapp`）：6 个凭证字段、1 个敏感字段、手机号去格式、拒绝通用设置入口、scope 校验/绑定、消息+模板双同步 |

### B. `ChannelAccountService` 重写

删除：`CREDENTIAL_KEYS`、`SECRET_KEYS`、`switch (channelType)`、`normalizeChannelType`、
`isWhatsAppChannel`、`assertCompatibleScope`、`bindProviderScope`。
注入 `ChannelTypeRegistry`，全部改为查表委托。构造器从 **4 个减到 2 个**（`@Autowired` 版 + 测试版），
随后在删除 wecom 死代码时进一步收敛为**唯一一个 3 参构造器**（见第六节第 1 项）。

### C. DTO 去掉渠道白名单

`CreateChannelAccountRequest.channelType` 去掉 `@Pattern`，保留 `@NotBlank`/`@Size`。

### D. 测试

- 更新 `ChannelAccountServiceTest`（8）、`ChannelAccountOwnerIsolationTest`（4）的构造方式
  （`ChannelAccountServiceWeComTest`（1）已于删除 wecom 死代码时整个删除，见第六节第 1 项）。
- 新增 `ChannelTypeRegistryTest`（5）：别名解析、大小写与空白、未注册类型、敏感字段并集、名称冲突。
- 新增 `ChannelTypeExtensionTest`（2）：**③ 的验收用例** —— 注册一个全新的 `sms` 渠道
  （不存在于代码库任何分支），走完绑定与同步链路。改造前这条用例必然落到 `default -> throw`。
  它是回归护栏：谁把渠道判断写回核心，它就会红。

## 四、行为保持核对

逐条对照原实现，**除下面列出的两处刻意变化外，行为完全一致**：

| 项 | 核对结果 |
|---|---|
| `CREDENTIAL_KEYS` → `credentialFields()` | email 12 个、chatapp 6 个，字段名逐一相同 |
| `SECRET_KEYS` → `secretFields()` 并集 | `{smtpPassword, imapPassword} ∪ {accessKeySecret}`，与原集合相同 |
| `normalizeIdentifier` | email = `trim().toLowerCase()`；chatapp = `trim()` 后去 `\s()\-`，与原实现逐字符等价 |
| 全部错误码与 HTTP 状态 | `RESOURCE_NOT_FOUND`/409 的 `CHANNEL_ACCOUNT_IDENTIFIER_IMMUTABLE`/`WHATSAPP_ONBOARDING_REQUIRED`/`CHANNEL_ACCOUNT_ALREADY_EXISTS`/`CHANNEL_ACCOUNT_INACTIVE`/`CHANNEL_ACCOUNT_INVALID_CREDENTIAL_FIELD`/`CHANNEL_ACCOUNT_INCOMPLETE_CREDENTIALS`/`CHANNEL_ACCOUNT_TYPE_UNSUPPORTED` 全部不变 |
| `createOrBind` 校验顺序 | 类型解析 → 可绑定校验 → 数量校验 → 标识归一化 → 凭证字段校验 → 完整性校验 → scope 校验 → 加密 → 落库 → scope 绑定，与原来逐步一致 |
| 同步返回结构 | email 返回 `SyncResult`；chatapp 返回 `{"messageSync":…, "templateSync":…}`，JSON 形状不变 |
| `update` / `getCredentials` / `unbind` / `list` | 未改动 |

### 两处刻意的契约变化（都需知晓）

1. **非法 `channelType` 的错误响应变了**。原来由 bean validation 拦下 →
   `400 TEMPLATE_VALIDATION_FAILED` + `fieldErrors`；现在由注册表判定 →
   `400 CHANNEL_ACCOUNT_TYPE_UNSUPPORTED`，无 `fieldErrors`。HTTP 状态同为 400。
   前端 `ChannelSettingsPage` 只发送 `email`（`configurableChannelTypes = ['email']`），不受影响。
2. **`sync(ownerId, id)` 遇到未注册渠道的异常类型变了**。原来 `IllegalArgumentException`（→400 `BAD_REQUEST`），
   现在 `ChannelAccountException`（→400 `CHANNEL_ACCOUNT_TYPE_UNSUPPORTED`）。
   实际不可达：wecom 账号的 `owner_user_id` 为 null（见 `ChannelAccountMapper.upsertWeComAccount`），
   `findByIdAndOwner` 永远匹配不到，所以这条路径进不来。

## 五、验收证据（全部实跑）

| 验收项 | 结果 |
|---|---|
| `mvn -o clean test-compile` | BUILD SUCCESS（505 主 / 343 测试；改造前 501 / 341） |
| 渠道专项 5 个测试类 | `Tests run: 20, Failures: 0, Errors: 0` |
| Web 层 4 个测试类 | `Tests run: 6, Failures: 0, Errors: 0` |
| **Spring 上下文装配** | `AppIntegrationTest`（真实 `@SpringBootTest` + Testcontainers，未 mock `ChannelAccountService`）`Tests run: 10, Failures: 0, Errors: 0` |
| **架构门禁** | `ArchitectureBoundaryTest` 通过；基线**保持 40 条不变**（新增类全落在已白名单的 `service.channel` 下） |
| **全量回归** | `mvn -o test` → `Tests run: 1429, Failures: 0, Errors: 0, Skipped: 0`（改造前 1422，新增 7 条用例） |
| 核心残留检索 | 全 `src/main` 无 `switch (…channelType…)`；无凭证字段 Map |

> 为什么要专门跑 `AppIntegrationTest`：其余 web 测试都用 `@MockitoBean` 替换了
> `ChannelAccountService`，即使 `ChannelTypeRegistry` 装不起来也不会暴露。只有真实上下文加载能验证装配。

> **后续变更（同日）**：删除 `ChannelAccountService.sync(UUID)` 死代码后（见第六节第 1 项），
> 测试类减 1（`ChannelAccountServiceWeComTest` 整个删除），编译规模变为 **505 主 / 342 测试**，
> 全量回归为 `Tests run: 1428, Failures: 0, Errors: 0`；架构门禁基线**仍为 40 条**；
> `AppIntegrationTest` 仍 10/10 —— 这也验证了去掉 `ObjectProvider` 后，新的单构造器
> （package-private + `@Autowired`）能被 Spring 正常装配。

## 六、未闭合项（需知晓，未在本轮处理）

1. **`ChannelAccountService.sync(UUID)` 死代码 —— 已删除（2026-09-20）**。
   该重载是 package-private 且无生产调用方（唯一的引用是只测它的单元测试）；它是
   `ObjectProvider<WeComChatDataSyncService>` 注入与 4 参构造器存在的唯一理由。
   连同 `requireWeComSync()`、`ChannelAccountServiceWeComTest` 一并删除后，
   `ChannelAccountService` 只剩一个 3 参构造器，`service.channel → service.wecom` 依赖归零。
   **行为不变**：wecom 的定时同步仍由 `WeComChatDataSyncRuntime.runOnce()`（60s）直接驱动
   `WeComChatDataSyncService.sync(context)`，不经过本类。
   `git log -S "requireWeComSync"` 指向 `9ce1377e`（拆分为独立 runtime 时带入），说明它比现有的账号隔离设计更早，
   属遗留搬迁残影而非有意设计。

2. **`ChannelAccountMapper.findAllByOwner` 在 SQL 里写死了 `channel_type in ('chatapp','email')`**。
   它是「哪些渠道是用户自管」的表述（wecom 账号 owner 为 null），所以不是纯粹的渠道白名单；
   但新增一个用户自管渠道仍需改这条 SQL。改法需要先确认库里有无历史 `whatsapp` 行，故本轮未动。

3. **`service/channel/ChatAppCapabilityService.context()` 写死 `"chatapp"/"whatsapp"` 判断**。
   它是 CAMS 能力探测服务、本身就是 WhatsApp 专属代码，不是「核心对渠道的感知」，
   故刻意不纳入注册表（否则等于把 WhatsApp 语义塞进通用 SPI）。

## 七、后续

- 第 ④ 步：阿里云 CAMS 供应商层独立（把 `ChatAppAccountCredentialsResolver` 等通用件从
  `channel/chatapp` 提为供应商层，让 chatapp 与 whatsapp 都依赖它）。
- 第 ② 步遗留的可选解耦：`chatapp/outbox → aitopic`（方向合法但跨业务域）。
