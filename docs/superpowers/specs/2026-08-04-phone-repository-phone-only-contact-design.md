# 电话仓库与电话唯一联系人设计

**日期：** 2026-08-04  
**状态：** 已确认  
**适用范围：** `demo/message-center-demo` 当前电话录音运行面

本设计 supersede：

- `2026-07-30-phone-call-transcription-local-funasr-design.md` 中“电话必须依赖已有联系人身份”的部分；
- `2026-08-03-local-call-phone-binding-design.md` 中“只在本地模式允许输入号码”的部分。

## 1. 目标

电话仓库保存所有电话录音、转录、修订和备注。联系人可以没有 WhatsApp、企业微信或邮件身份，只以电话号码作为唯一联系身份。新建电话记录时联系人和电话号码都必须填写；联系人和号码都支持选择已有值或填写新值；备注可选。

“无联系人”在本设计中不表示没有联系人，而表示该联系人没有任何数字渠道身份，只有 `phone:` 身份。

## 2. 核心决策

- `phone:<纯数字>` 是正式联系人身份，不是临时上传字段。
- 电话唯一联系人可以进入统一联系人列表，即使没有任何普通消息。
- 有数字渠道的联系人可以继续绑定一个或多个电话身份；合并、拆分仍由现有联系人分组 owner 管理。
- 电话仓库是跨联系人的独立查询面；联系人时间线是按当前联系人组投影出的子集。
- 新录音必须同时拥有联系人身份和电话号码；不能创建缺少其中任一项的记录。
- 备注属于电话记录事实，可编辑并保留修订历史；不写入普通消息和 `phone_notes`。

## 3. 唯一 owner 与数据流

```text
电话仓库上传表单
  -> CallRecordHttpAdapter
  -> UnifiedMessageStore.ensurePhoneContact / bindPhonePoint
  -> CallRecordService
  -> FileCallRecordRepository + LocalAudioStore
  -> TranscriptionWorker -> FunASR
```

- `UnifiedMessageStore` 继续拥有联系人分组、联系人展示名称、电话身份合并/拆分语义，并允许没有消息的电话组进入联系人投影。
- `CallRecordService` 继续拥有电话记录状态机、电话归属校验、备注长度和并发版本校验。
- `CallRecordRepository` 保存电话记录事实，不保存可推导的联系人名称或数字渠道列表。
- `CallRecordHttpAdapter` 只做表单映射、认证和错误投影；不在 HTTP 层推断联系人归属。
- `PhoneRepository` 不是第二套联系人数据库，而是电话记录的全局查询投影，底层读取同一个 `CallRecordRepository`。

## 4. 联系人身份合同

### 4.1 规范化

输入 `+86 138-0000-0000` 规范化为 `phone:8613800000000`。号码必须去除显示分隔符后只包含 6 至 20 位数字。服务端统一规范化，浏览器不得自行生成最终身份。

### 4.2 选择或填写联系人

- 选择已有联系人：请求携带该联系人当前 primary point 或 contact id。
- 填写新联系人：请求携带非空显示名称和电话号码；服务端创建电话组，primary point 为规范 `phone:` 身份。
- 已有联系人填写新电话号码：服务端将新的 `phone:` point 加入该联系人组；若该号码已经属于其他联系人组，返回 409，不静默迁移。
- 仅电话号码联系人显示名称默认为号码；用户可在资料编辑中设置备注名称。

## 5. 电话记录模型变化

现有模型保留：

```text
contactAnchorPointId   必填，创建时联系人组中的稳定身份锚点
phonePointId           必填，规范 phone:<digits>
```

新增：

```text
note                   可选，自由文本，最大 4,000 个 Unicode code points
```

规则：

- `phonePointId` 必须属于 `contactAnchorPointId` 所在的联系人组。
- 电话唯一联系人创建时，`contactAnchorPointId == phonePointId`。
- 联系人合并或拆分不改写记录快照；查询时按当前联系人组解析归属。
- 备注更新使用 `expectedVersion`，与人工转录修订共享版本冲突语义。
- 录音、原始转录、分段和备注均保留；原始音频路径不出现在 API 响应之外的任意用户字段中。

## 6. HTTP 合同

### 6.1 电话联系人绑定

```text
POST /api/v1/phone-contacts
Content-Type: application/json

{
  "contactId": "可选，已有联系人",
  "contactName": "联系人名称，填写新联系人时必填",
  "phoneNumber": "+86 138-0000-0000"
}
```

成功返回：

```json
{
  "contactId": "phone:8613800000000",
  "phonePointId": "phone:8613800000000",
  "displayName": "采购联系人"
}
```

### 6.2 创建电话记录

现有 multipart 接口继续使用：

```text
POST /api/v1/contacts/{contactId}/call-records
```

必填字段：

```text
phonePointId
direction
occurredAt
clientRequestId
file
```

新增可选字段：

```text
note
```

服务端在接收大文件之前完成联系人、号码、方向、时间、备注字段校验。

### 6.3 电话仓库查询

```text
GET /api/v1/phone-repository?cursor=&limit=50&query=
GET /api/v1/call-records/{id}
PATCH /api/v1/call-records/{id}/note
```

电话仓库按 `occurredAt DESC, id DESC` 稳定分页，可按电话号码、联系人名称和备注检索。查询结果包含联系人名称、电话号码、备注、转录状态和播放入口，但不包含本地路径、播放 Cookie 或密钥。

## 7. UI 行为

- 增加独立“电话仓库”入口，不要求先选聊天联系人。
- 上传表单中联系人为可搜索选择器，也支持“新建联系人”。
- 电话号码为可搜索选择器，也支持手动输入；两者均为必填。
- 新联系人+新号码先调用联系人绑定接口，成功后才提交音频。
- 备注为可选多行输入；列表和详情都显示备注，详情支持保存修订。
- 已绑定联系人上传后，电话记录同时出现在电话仓库和联系人时间线。
- 只有电话身份的联系人显示在联系人列表和电话仓库中，不显示伪造的 WhatsApp/企业微信/邮件渠道。
- 表单任一步失败时保留文件选择、联系人、号码和备注，不显示伪成功。

## 8. 错误与权限

- 联系人或电话号码缺失：400 `PHONE_CONTACT_REQUIRED`。
- 号码格式错误：400 `PHONE_NUMBER_INVALID`。
- 联系人不存在：404 `CONTACT_NOT_FOUND`。
- 号码属于其他联系人：409 `PHONE_POINT_CONFLICT`。
- 备注超过上限：400 `CALL_RECORD_NOTE_INVALID`。
- 非 viewer 或 viewer 过期：401；不能通过请求体伪造 actor。
- 电话仓库查询只返回当前 viewer 有权访问的租户记录；现阶段沿用当前实例 viewer 的 tenant-wide 权限边界。

## 9. 验收

- 创建只有电话身份的联系人，保存录音并显示在联系人列表、电话仓库和联系人时间线。
- 已有联系人新增电话号码后，号码归入该联系人组；重复绑定幂等；跨联系人冲突返回 409。
- 联系人和电话号码均可选择或手动填写；任一缺失均拒绝上传。
- 备注可为空；填写后在电话仓库和详情中显示，修改使用版本校验并保留当前值。
- 录音转录、重试、播放会话、时间线轮询和现有数字渠道行为不回归。
- 聚焦单元/HTTP/UI 测试、Maven 全量测试、OpenAPI 合同测试和 `git diff --check` 全部通过。

## 10. 非目标

- 不新建第二套联系人主数据存储。
- 不把电话身份伪装成数字渠道消息。
- 不允许新建缺少联系人或号码的电话记录。
- 不改变 FunASR 协议、音频上限、转录状态机或安全播放会话机制。
