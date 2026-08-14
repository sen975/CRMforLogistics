# WhatsApp 公共模板可视化工作台设计

**状态：** 已确认，待实施计划

**日期：** 2026-08-14

## 1. 目标

把管理员 `公共模板库` 从表格和小型预览 Drawer 改为可扫描的消息预览卡片网格，并提供宽详情工作台。
详情同时支持两条明确分离的业务路径：

- `复制此模板`：沿用公共模板内容，调用现有 `CopyTemplate`。
- `基于此模板创建`：把公共模板转换为可编辑草稿，复用现有 `CreateChatappTemplate` 创建和送审流程。

列表和详情必须使用同一个结构化消息预览 owner，支持参数占位符和示例值两种展示，不得根据扁平字符串
或 UI 临时规则猜测模板内容。

## 2. 当前问题

现有 `PublicTemplateLibrary` 使用 Ant Design Table 展示公共模板，预览使用宽度 560px 的 Drawer，复制使用
独立 Modal。该结构可以完成查询和复制，但无法像模板库一样快速比较真实消息内容，也把模板预览、变量、按钮
和复制设置拆散在多个表面中。

更关键的是，当前公共模板投影把 `pageName` 和 `rcsTitle.realContent` 一起压入 `pages: string[]`，再把所有
按钮压入全局 `buttons[]`。真实响应例如：

```json
{
  "pageName": "page1",
  "rcsTitle": {
    "realContent": "$(text)，您好：您的新帐号已成功创建。"
  },
  "pageLinkList": [
    {
      "linkName": "验证帐号",
      "linkType": "visitWebsite",
      "action": { "url": "https://www.example.com" }
    }
  ]
}
```

现有投影会把它变成 `['page1', '$(text)...']`，导致 UI 可能把 `page1` 错当正文，也无法知道按钮属于哪一页。
该合同不足以支撑可信预览和公共模板到自定义模板的转换，必须先结构化修正。

## 3. 产品边界

### 3.1 本轮包含

- 公共模板响应的结构化页面投影。
- 响应式消息预览卡片网格。
- 宽详情工作台和 `参数 / 示例` 预览切换。
- 页面级预览和多页面选择。
- 现有 `CopyTemplate` 复制路径。
- 公共模板到现有模板编辑器草稿的转换。
- 无法完整转换时的人工补全与阻断提交。
- 桌面和移动端真实渲染验收。

### 3.2 本轮不包含

- 新数据库表或字段。
- 新的模板创建后端入口。
- 第二套模板编辑器。
- 公共模板直接进入发送选择器。
- 绕过审核或自动设置 `allowSend=true`。
- 未经合同支持的 Carousel、Flow、商品模板或其他高级组件创建。
- 将控制台 Cookie、HAR 原始内容、AccessKey 或 provider 原始 JSON 暴露给前端。

## 4. Owner 与组件边界

### 4.1 后端 owner

`PublicTemplateResponseParser` 是 `ListBaseTemplate` 响应结构的唯一解析 owner，负责把 provider 页面、正文、
变量和页面内按钮转换为稳定领域投影。Controller 只返回该投影，不拥有页面识别或变量语法规则。

### 4.2 前端 owner

- `PublicTemplateLibrary`：账号、筛选、分页、加载、错误和查询缓存。
- `PublicTemplateCardGrid`：响应式模板卡片集合。
- `PublicTemplateCard`：单个模板的可访问入口和紧凑预览。
- `TemplateMessagePreview`：参数和示例两种消息渲染的唯一 owner。
- `PublicTemplateDetailModal`：详情工作台、多页选择、复制草稿和两个业务动作。
- `publicTemplateToEditorDraft`：公共投影到自定义编辑草稿的唯一转换 owner。
- `TemplateEditorDrawer`：创建和修改模板的唯一编辑器。
- `TemplatesPage`：创建、修改和复制结果接线；公共库子组件不能绕过它直接拥有创建状态机。

`TemplateEditorDrawer` 不接收伪造的 `TemplateAdmin`。它应支持独立的 `TemplateEditorInitialValue`，明确区分
已有模板编辑和公共模板初始化草稿。

## 5. 公共模板领域合同

公共模板内容改为结构化页面：

```ts
interface PublicTemplatePage {
  name: string | null;
  text: string | null;
  buttons: PublicTemplateButton[];
}

interface PublicTemplateContent {
  templateName: string | null;
  sceneTemplateName: string | null;
  externalTemplateCode: string | null;
  languageCode: string | null;
  category: string | null;
  pages: PublicTemplatePage[];
  variables: PublicTemplateVariable[];
}
```

原来的全局 `buttons` 字段删除，按钮只属于具体页面。当前公共模板功能尚未形成外部稳定 API，因此不保留会污染
新合同的双路径兼容字段。

解析约束继续保持现有上界，并将按钮数量限制应用到每页和整份响应。正文、页面名、按钮名、URL 和变量字段都
必须经过类型和长度校验；坏响应继续返回 `PUBLIC_TEMPLATE_RESPONSE_INVALID`。

## 6. 预览语义

`TemplateMessagePreview` 接收结构化页面、变量和展示模式，不读取 provider 原始响应。

### 6.1 参数模式

- 保留正文中的变量位置。
- 将已声明变量的 `$(code)` 和 `{{code}}` 显示为蓝色变量标记。
- 未声明变量不伪装成合法参数，按普通文本显示并产生转换问题。
- 列表卡片默认使用参数模式。

### 6.2 示例模式

- 使用变量 `defaultValue/example` 替换对应参数。
- 缺少示例值时继续显示参数标记，不填充假数据。
- 详情工作台通过分段控制在参数和示例模式之间切换。

### 6.3 多页面

- 卡片显示第一页可预览正文。
- 详情显示页面选择器，切换后正文和按钮同步更新。
- 页面名只用于选择和标识，不能作为消息正文渲染。

## 7. 公共模板到编辑草稿的转换

`publicTemplateToEditorDraft` 返回：

```ts
interface PublicTemplateConversionResult {
  initialValue: TemplateEditorInitialValue;
  sourceTemplate: PublicTemplate;
  selectedPageIndex: number | null;
  issues: PublicTemplateConversionIssue[];
}
```

转换规则：

- 名称预填为 `<公共模板名称>_custom`，仍需通过现有名称校验且允许用户修改。
- 支持的语言和类别直接映射；未知值产生阻断问题。
- 单个有效页面自动选择；多个有效页面要求用户明确选择。
- 选中页面正文中的已声明 `$(code)` 转为创建合同使用的 `{{code}}`。
- 变量默认值映射到现有 `examples: Record<string, string[]>`。
- `visitWebsite` 映射为 URL 按钮。
- 能被当前合同明确识别的电话和快速回复类型才允许映射。
- 未知按钮类型不进入创建命令，并产生阻断问题。
- 缺失正文、变量声明冲突、未知类别/语言和多页未选择都产生阻断问题。

编辑器允许在存在问题时打开并修改，但 `提交创建` 在阻断问题未解决前保持禁用。问题必须来自转换结果和当前
编辑状态的结构化校验，不能依赖用户勾选“我已处理”来绕过事实校验。

## 8. 页面布局

### 8.1 展示页

- 筛选区保持一行工作型工具栏，可在窄宽度换行。
- 卡片网格采用稳定最小宽度和固定预览高度：宽屏 3 列、普通桌面 2 列、移动端 1 列。
- 卡片是独立重复项，圆角不超过 8px。
- 消息画布使用浅中性灰，消息内容使用白色，变量使用现有 Ant Design 蓝色系。
- 卡片底部显示模板名称、语言和类别；整卡支持鼠标和键盘打开详情。
- 长内容在预览区域内截断，不改变同行卡片尺寸。
- 加载使用同尺寸 Skeleton；错误提供重试；分页位于网格下方。

### 8.2 详情工作台

- 桌面 Modal 宽约 1120px，最大宽度受视口约束。
- 左侧约 60%：模板信息、复制名称、页面选择、变量表格和按钮信息。
- 右侧约 40%：固定预览和 `参数 / 示例` 分段控制。
- 移动端使用全屏纵向布局和固定底部操作区，预览与表单不得重叠。
- `复制此模板` 是主操作；`基于此模板创建` 是独立次操作。
- 复制模式下正文、变量编码和按钮 URL 只读；变量示例只作为本地审计快照可编辑。

### 8.3 自定义编辑器

- 复用现有 `TemplateEditorDrawer`。
- 显示公共模板来源和结构化待补全项。
- 保留源模板预览，供用户对照编辑结果。
- 多页模板必须先选定来源页面。
- 编辑器继续使用现有 Header、BODY、Footer、按钮、素材和示例字段，不增加 provider 私有字段。

## 9. 数据流

```text
ListBaseTemplate
  -> PublicTemplateResponseParser
  -> PublicTemplatePage[]
  -> PublicTemplateLibrary
  -> PublicTemplateCard / PublicTemplateDetailModal
  -> TemplateMessagePreview
```

复制路径：

```text
PublicTemplateDetailModal
  -> CopyTemplateCommand(code, language, templateName)
  -> PublicTemplateApplicationService
  -> CopyTemplate
  -> reconciliation / sync
  -> APPROVED + allowSend=true 后进入发送选择器
```

自定义路径：

```text
PublicTemplateDetailModal
  -> publicTemplateToEditorDraft
  -> TemplateEditorDrawer
  -> TemplatesPage create mutation
  -> TemplateCreateRequest
  -> CreateChatappTemplate
  -> 重新审核
```

## 10. 并发、账号和错误状态

- React Query key 继续包含账号 ID 和完整公共模板查询。
- 账号切换时关闭详情、复制草稿和公共模板初始化草稿。
- 复制继续复用稳定 `clientRequestId`，同一弹窗失败重试不生成新键。
- 旧账号或旧草稿的 success/error 不能关闭或污染当前账号工作台。
- 列表失败显示结构化 message 和 trace ID，并提供重试。
- 复制失败保留名称和变量审计值。
- 自定义转换问题与创建 API 错误分层展示，不能把 provider 错误伪装为字段转换问题。
- 完整 provider body、请求参数、Cookie、密钥和控制台内部字段不得进入浏览器 bundle、错误提示或普通日志。

## 11. 性能与可访问性

- `TemplateMessagePreview` 对变量查找建立 Map，避免每个文本片段重复扫描变量列表。
- 卡片渲染数据由稳定结构派生；复杂转换在 adapter 中完成，不在每张卡片的 render 路径重复计算。
- 列表分页保持服务端分页，不一次加载全部公共模板。
- 卡片、分段控制、页面选择和底部操作具备可访问名称、键盘焦点和可见 focus 状态。
- 预览文本允许正常换行，长 URL 不得撑破布局。
- 固定卡片尺寸、Modal 列宽和移动端断点，避免加载和切换模式造成布局跳动。

## 12. 测试与验收

### 12.1 后端

- fixture 测试结构化页面名、正文、变量和页面内按钮。
- parser 测试错误类型、长度上界、多页、每页/累计按钮上界和坏响应。
- Controller 合同测试不再返回扁平全局按钮字段。

### 12.2 前端单元与组件测试

- 参数模式识别 `$(code)` 和 `{{code}}`。
- 示例模式替换、缺失示例回退和未声明变量处理。
- 卡片只显示正文，不显示 `pageName`。
- 多页选择同步正文和按钮。
- 单页公共模板自动生成编辑草稿。
- 多页、未知按钮、缺失正文和未知语言/类别产生阻断问题。
- 复制命令仍不包含 URL、正文、账号 ID 或 provider 私有字段。
- 自定义路径复用现有 create mutation，不调用 CopyTemplate。
- 账号切换和 A -> B -> A 的旧异步结果隔离继续通过。

### 12.3 构建与浏览器验收

- 运行前端完整 Vitest/source tests 和生产构建。
- 运行公共模板后端 focused suite 和 package。
- 浏览器验证桌面宽屏、普通桌面和移动端。
- 检查加载、空列表、错误、参数模式、示例模式、多页、复制失败保留草稿和自定义待补全。
- 检查页面无框架错误覆盖层、无相关 console error/warning、无文本重叠和水平滚动陷阱。

## 13. 停止条件

出现以下情况时停止对应转换或操作，不增加临时兼容分支：

- provider 页面结构无法可靠识别正文和页面内按钮。
- 公共模板包含现有创建合同不支持的组件且用户尚未补全。
- 变量声明与正文参数无法一一对应。
- 多页模板未明确选择创建来源页面。
- 目标账号不再是有效 WhatsApp/ChatApp 账号。
- 创建或复制结果未知且无法通过现有 reconciliation 对账。

## 14. 对现有文档的影响

本设计替代 `2026-08-12-whatsapp-template-remarks-and-library-design.md` 中以下前端限制：

- 公共模板库使用表格和小型预览 Drawer。
- 只实现 `复制到我的模板`。
- 不实现控制台“自定义模板”分支。

原文关于公共模板不能直接发送、CopyTemplate 参数边界、账号校验、审核状态和发送资格的规则继续有效。
