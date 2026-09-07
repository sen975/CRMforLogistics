export function contactDisplayName(contact: {
  remark?: string | null;
  displayName?: string | null;
}): string {
  const remark = contact.remark?.trim();
  if (remark) return remark;
  const displayName = contact.displayName?.trim();
  return displayName || '未命名';
}
