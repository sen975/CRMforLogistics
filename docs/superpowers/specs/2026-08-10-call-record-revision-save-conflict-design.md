# 通话转录修订保存冲突设计

**日期：** 2026-08-10
**状态：** 已确认
**适用范围：** `demo/message-center-spring/` 通话详情人工修订保存

## 1. 问题与证据

通话详情只在转录状态为 `queued` 或 `processing` 时轮询。记录进入 `completed` 后，React Query 可以长期保留当时的详情版本。人工修订保存直接提交缓存中的 `record.version`，如果后端版本已经因为转录完成、备注更新或其他非转录修订操作递增，接口返回 `409 TRANSCRIPT_VERSION_CONFLICT`。

截图对应记录 `37976e17-22de-40b2-8e92-264d9123c795` 的数据库版本为 `6`，修订记录数为 `0`。使用旧版本 `5` 执行无副作用 PATCH 探针，后端稳定返回：

```json
{
  "code": "TRANSCRIPT_VERSION_CONFLICT",
  "message": "Transcript version has changed"
}
```

前端当前捕获所有异常后统一显示“保存失败，版本可能已更新”，因此真实版本冲突和其他失败无法区分。

## 2. 唯一主线

采用“保存前刷新版本，但不覆盖并发转录修订”的策略：

1. 用户进入人工修订编辑状态时，保存当时的 `currentRevisionId` 作为修订基线。
2. 用户点击保存时重新获取最新通话详情。
3. 最新 `currentRevisionId` 与编辑基线一致时，说明期间没有其他人工转录修订；使用最新 `version` 提交当前草稿。
4. 最新 `currentRevisionId` 与编辑基线不一致时，保留当前草稿并提示转录稿已被其他操作更新，禁止自动覆盖。
5. 保存成功后用响应更新 `['callRecord', callRecordId]` 缓存并退出编辑状态。

不采用以下路线：

- 不直接用最新版本强制保存，因为会覆盖其他人的人工修订。
- 不取消后端乐观锁。
- 不新增数据库字段，不改变修订历史合同。
- 不增加新的详情页面、弹窗或视觉结构。

## 3. Owner 与事务边界

前端 `CallRecordDetail` 拥有编辑基线、保存前刷新和用户错误提示，只消费后端提供的 `version` 与 `currentRevisionId`。

后端 `CallRecordService.revise` 拥有人工修订的原子写入。修订表插入与 `call_records.current_revision_id/version` 更新必须处于同一事务：如果乐观锁更新失败，修订插入必须回滚，不能产生 API 报错但历史中出现孤立修订的状态。

## 4. 错误语义

- `TRANSCRIPT_VERSION_CONFLICT`：刷新最新详情，保留草稿，提示转录稿已更新。
- 其他服务端错误：显示服务端可用错误信息或通用“转录稿保存失败”，不得误报为版本冲突。
- 网络失败：保留草稿和编辑状态，允许用户再次保存。
- 保存按钮在请求期间禁用，避免同一草稿重复提交。

## 5. 测试与验收

前端测试覆盖：

- 编辑基线与最新 `currentRevisionId` 相同时，使用最新 `version` 保存。
- `currentRevisionId` 变化时拒绝自动保存。
- `null` 修订基线可以与最新 `null` 正确比较。
- 组件保存链先获取最新详情，再决定是否调用修订接口。

后端测试覆盖：

- `revise` 使用请求中的旧版本执行乐观锁更新，并返回递增后的版本。
- `revise` 具有事务边界，确保插入修订与更新主记录同成同败。

验收命令：

```bash
cd demo/message-center-spring/frontend
npm test
npm run build

cd ../backend
mvn -Dtest=CallRecordServiceTest test
```

浏览器验收路径：打开电话仓库 -> 打开右侧通话详情 -> 编辑人工修订稿 -> 保存 -> 显示成功提示与新增修订历史。若内置浏览器没有可用实例，必须明确记录此项未执行，不能用构建结果冒充真实交互验收。
