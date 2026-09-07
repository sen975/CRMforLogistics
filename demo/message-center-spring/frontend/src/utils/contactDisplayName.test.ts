import { describe, expect, it } from 'vitest';
import { contactDisplayName } from './contactDisplayName';

describe('contactDisplayName', () => {
  it('prefers a non-blank remark over the original display name', () => {
    expect(contactDisplayName({ remark: '重点客户', displayName: '微信昵称' })).toBe('重点客户');
  });

  it('falls back to display name and then an explicit unnamed label', () => {
    expect(contactDisplayName({ remark: '  ', displayName: '微信昵称' })).toBe('微信昵称');
    expect(contactDisplayName({ remark: null, displayName: '' })).toBe('未命名');
  });
});
