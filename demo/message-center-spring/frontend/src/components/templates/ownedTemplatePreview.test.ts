import { describe, expect, it } from 'vitest';
import type { TemplateAdmin, TemplateComponent } from '../../api/types';
import { ownedTemplatePreview } from './ownedTemplatePreview';

function template(components: TemplateComponent[]): TemplateAdmin {
  return {
    id: 'template-1',
    version: 1,
    accountId: 'account-1',
    templateCode: '1265167869412990976',
    name: 'shipment_arrival_notice',
    remark: null,
    displayName: '到货通知',
    language: 'zh_CN',
    category: 'UTILITY',
    reviewStatus: 'SUSPENDED',
    providerAuditStatus: 'sendFail',
    rejectionReason: null,
    allowSend: false,
    desiredAllowSend: false,
    permissionSyncStatus: 'IDLE',
    permissionSyncError: null,
    components,
    examples: {},
    messageSendTtlSeconds: null,
    qualityScore: null,
    providerUpdatedAt: null,
    lastSyncedAt: null,
    deletedAt: null,
  };
}

function component(type: TemplateComponent['type'], text: string | null): TemplateComponent {
  return { type, headerFormat: type === 'HEADER' ? 'TEXT' : null, text, mediaAssetId: null, buttons: [] };
}

describe('ownedTemplatePreview', () => {
  it('把页脚单独带出去，不拼进正文', () => {
    const preview = ownedTemplatePreview(template([
      component('BODY', '$(customer_name)，您好。您订购的货物预计还有 $(days) 天送达 $(destination)。'),
      component('FOOTER', '如需调整请直接回复本条消息'),
    ]));

    // 并进正文，用户就会把它读成「正文里多出来的一句无关的话」—— 这是这条测试要钉住的。
    expect(preview.page.text).toBe('$(customer_name)，您好。您订购的货物预计还有 $(days) 天送达 $(destination)。');
    expect(preview.page.footer).toBe('如需调整请直接回复本条消息');
  });

  it('没有 FOOTER 组件时给出空值，渲染层据此不生成页脚节点', () => {
    const preview = ownedTemplatePreview(template([component('BODY', '您好')]));

    expect(preview.page.footer ?? null).toBeNull();
  });

  it('文本头部与正文之间仍留一个空行，页脚不参与这段拼接', () => {
    const preview = ownedTemplatePreview(template([
      component('HEADER', '到货通知'),
      component('BODY', '您好'),
      component('FOOTER', '退订请回复 TD'),
    ]));

    expect(preview.page.text).toBe('到货通知\n\n您好');
    expect(preview.page.footer).toBe('退订请回复 TD');
  });
});
