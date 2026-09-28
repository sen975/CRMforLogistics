/**
 * 列表 / 会话标签：**备注优先**。
 *
 * 「给人扫列表」的语义 —— 联系人列表、会话标题、发送与群发的选择列表都用它。
 * 渠道同步来的昵称在现实里常是 `calm1026` / `wxid_*` / 手机号，当列表名没有辨识度，
 * 所以列表用用户自己写的备注来认人（产品语义，2026-09-24 明确）。
 *
 * 注意：**档案 / 详情页不要调用这个函数**。详情要同时给出两个独立字段 ——
 * 「名称」= 渠道真名（`contact.displayName`）、「备注」= `contact.remark`；
 * 用本函数会让两行显示同一段文字，看起来就是「备注把名称覆盖了」。
 */
export function contactDisplayName(contact: {
  remark?: string | null;
  displayName?: string | null;
}): string {
  const remark = contact.remark?.trim();
  if (remark) return remark;
  const displayName = contact.displayName?.trim();
  return displayName || '未命名';
}
