import { describe, expect, it } from 'vitest';
import type { SharedTemplate } from '../../api/types';
import { hasLeadingOrTrailingVariable, hasVariable, templatePermissionState } from './templateUi';

function template(overrides: Partial<SharedTemplate>): SharedTemplate {
  return {
    id: 'template-1',
    templateCode: 'shipping_notice',
    language: 'zh_CN',
    category: 'UTILITY',
    reviewStatus: 'APPROVED',
    allowSend: true,
    ...overrides,
  } as SharedTemplate;
}

/**
 * 「暂停发送」的出口判据。
 *
 * 2026-09-29 实测（真实实例 + 真实 CAMS）：同一把空间凭据、同一个模板，只改 allowSend ——
 * `true` 成功，`false` 必然拿到 CAMS 的 `ERR-COMMON-001`（`code: 400, System error`）。
 * Meta 侧也没有业务可控的暂停接口：模板的 PAUSED 状态由 Meta 按质量评分自己给。
 * 所以对非营销类模板，这个动作不是「可能失败」，是根本不成立 —— 界面上不该有出口。
 */
describe('templatePermissionState', () => {
  it('通知类模板不给「暂停发送」出口，并把原因写出来', () => {
    const state = templatePermissionState(template({ category: 'UTILITY', allowSend: true }));

    expect(state.actionLabel).toBe('暂停发送');
    expect(state.toggleUnavailable).toBe(true);
    expect(state.toggleUnavailableReason).toContain('营销');
  });

  it('验证类模板同样不给出口（不是只挡 UTILITY 一个值）', () => {
    const state = templatePermissionState(template({ category: 'AUTHENTICATION', allowSend: true }));

    expect(state.toggleUnavailable).toBe(true);
  });

  it('营销模板照旧可以暂停 —— 只挡必然失败的动作，不删功能', () => {
    const state = templatePermissionState(template({ category: 'MARKETING', allowSend: true }));

    expect(state.toggleUnavailable).toBe(false);
    expect(state.toggleUnavailableReason).toBeNull();
  });

  it('类别未知时不误伤「暂停」以外的事：恢复发送仍按审核状态判可用', () => {
    const approved = templatePermissionState(
      template({ category: 'UTILITY', allowSend: false, reviewStatus: 'APPROVED' }));
    expect(approved.actionLabel).toBe('恢复发送');
    expect(approved.toggleUnavailable).toBe(false);
    expect(approved.toggleDisabled).toBe(false);

    const rejected = templatePermissionState(
      template({ category: 'UTILITY', allowSend: false, reviewStatus: 'REJECTED' }));
    expect(rejected.toggleDisabled).toBe(true);
  });
});

/**
 * 「变量落在正文首尾」是平台的硬规则（拒审原话 Variables can't be at the start or end of
 * the template，拒审码 Leading or Trailing Params Not Allowed），后端
 * WhatsAppTemplateValidator 按同一口径拦。这里钉前端那一半 ——
 * 两边判据不一致时，用户看到的是「点了提交什么也没发生」这种最难查的故障。
 */
describe('hasLeadingOrTrailingVariable', () => {
  it('变量打头、变量收尾都算违规', () => {
    expect(hasLeadingOrTrailingVariable('$(customer)，您好')).toBe(true);
    expect(hasLeadingOrTrailingVariable('您好 $(customer)')).toBe(true);
  });

  it('垫了空白的写法一样算违规 —— 意图仍是让变量打头', () => {
    expect(hasLeadingOrTrailingVariable('  $(customer) 您好')).toBe(true);
    expect(hasLeadingOrTrailingVariable('\n您好 $(customer)\t')).toBe(true);
  });

  it('变量两侧都有固定文字就合规', () => {
    expect(hasLeadingOrTrailingVariable('您好，$(customer)，您的货物已发出')).toBe(false);
  });

  it('没有变量、正文为空都不算违规（空正文由另一条阻断项管）', () => {
    expect(hasLeadingOrTrailingVariable('您好')).toBe(false);
    expect(hasLeadingOrTrailingVariable('')).toBe(false);
    expect(hasLeadingOrTrailingVariable('   ')).toBe(false);
  });

  it('两个变量相邻不在这里判 —— 那条平台口径未定，与后端保持一致', () => {
    expect(hasLeadingOrTrailingVariable('您好，$(first)$(last)，请查收')).toBe(false);
  });
});

/** 页脚是「一个变量都不能有」，比正文更严：平台的 FOOTER 组件不支持参数。 */
describe('hasVariable', () => {
  it('页脚只要出现变量就算违规，位置无关', () => {
    expect(hasVariable('退订请回 $(name)')).toBe(true);
    expect(hasVariable('$(name) 退订请回复')).toBe(true);
    expect(hasVariable('$(a) 与 $(b)')).toBe(true);
  });

  it('纯文本页脚照旧放行', () => {
    expect(hasVariable('退订请回复 TD')).toBe(false);
    expect(hasVariable('')).toBe(false);
  });
});
