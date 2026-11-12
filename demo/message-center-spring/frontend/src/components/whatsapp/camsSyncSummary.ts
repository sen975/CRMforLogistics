import type { AdminWhatsAppSyncResult } from '../../api/types';

/**
 * The sync imports only numbers CAMS reports ACTIVE, so a space whose only number falls short used
 * to answer "导入 0，刷新 0，不可用 0" and look empty with no reason given. Name the numbers CAMS did
 * answer for, with the statuses it gave, so an unimported number is visible as such.
 */
export function camsSyncSummary(result: AdminWhatsAppSyncResult): string {
  const counts = `导入 ${result.importedCount}，刷新 ${result.refreshedCount}，不可用 ${result.unavailableCount}`;
  const rejected = (result.providerPhones ?? []).filter((phone) => !phone.accepted);
  if (!rejected.length) return `同步完成：${counts}`;
  const detail = rejected
    .map((phone) => `${phone.maskedPhone}（${phone.providerStatus || '未知状态'} / ${phone.verificationStatus || '未知验证'}）`)
    .join('、');
  return `同步完成：${counts}。CAMS 返回但未导入 ${rejected.length} 个号码：${detail}`;
}

/** True when CAMS answered for a number the sync would not import, which is worth a second look. */
export function hasUnimportedProviderPhones(result: AdminWhatsAppSyncResult): boolean {
  return (result.providerPhones ?? []).some((phone) => !phone.accepted);
}
