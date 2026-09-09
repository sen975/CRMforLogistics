# 电话联系人回填

历史电话记录可能缺少 `contact_id` 或电话身份。回填服务按当前系统电话归属规则补齐关联：联系人归属唯一以 `contacts.created_by` 为准；尚未绑定联系人的孤立电话才使用有效的 `call_records.created_by` 作为创建者。电话身份的 `identity_scope` 始终是创建者用户 UUID，不跨用户复用联系人。

管理员登录后调用：

```text
POST /api/v1/admin/call-records/contact-backfill?limit=200
```

`limit` 默认 200，最大 1000。接口不接受 `ownerId`。响应包含 `processed`、`createdContacts`、`linkedExistingContacts`、`skippedUnowned` 和 `failed` 五项统计。

服务按 `call_records.version` 做乐观更新，可重复执行；单条失败不会中断同批其他记录。有 `contact_id` 时先读取该联系人的 `created_by`；没有联系人时才校验电话记录的 `created_by`。无法从这两个字段得到有效创建者时，记录不会被认领。`call_records.owner_user_id` 仅作为历史兼容字段保留，不参与联系人权限判断或回填归属决策。

执行前先运行同目录的 `phone-contact-backfill.sql` 只读核对脚本。禁止直接执行全表 `UPDATE`，联系人和电话身份必须通过应用服务创建，以保留唯一约束、审计字段和并发收敛逻辑。

回填完成后重新运行核对 SQL，并检查接口计数。需要停止时只需停止继续调用接口；已创建的联系人和身份不应手工删除回滚，需按业务审计流程处理。
