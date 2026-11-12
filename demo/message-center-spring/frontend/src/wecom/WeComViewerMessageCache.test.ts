import { describe, expect, it } from 'vitest';
import { WeComViewerMessageCache } from './WeComViewerMessageCache';

describe('WeComViewerMessageCache', () => {
  it('隔离 viewer token 和 contactPointId，并在命中时返回 secretKey', () => {
    const cache = new WeComViewerMessageCache({ maxEntries: 2, ttlMs: 60_000, now: () => 100 });

    cache.set('token-a', 'wecom:contact-a', { msgid: 'm1', secretKey: 's1' });

    expect(cache.get('token-a', 'wecom:contact-a', 'm1')).toEqual({ msgid: 'm1', secretKey: 's1' });
    expect(cache.get('token-a', 'wecom:contact-b', 'm1')).toBeUndefined();
    expect(cache.get('token-b', 'wecom:contact-a', 'm1')).toBeUndefined();
  });

  it('TTL 到期和容量超限会淘汰条目', () => {
    let now = 0;
    const cache = new WeComViewerMessageCache({ maxEntries: 2, ttlMs: 10, now: () => now });

    cache.set('token', 'wecom:contact', { msgid: 'm1', secretKey: 's1' });
    cache.set('token', 'wecom:contact', { msgid: 'm2', secretKey: 's2' });
    expect(cache.get('token', 'wecom:contact', 'm1')).toEqual({ msgid: 'm1', secretKey: 's1' });

    cache.set('token', 'wecom:contact', { msgid: 'm3', secretKey: 's3' });
    expect(cache.get('token', 'wecom:contact', 'm2')).toBeUndefined();

    now = 11;
    expect(cache.get('token', 'wecom:contact', 'm1')).toBeUndefined();
  });

  it('可以批量写入并按 token 清理', () => {
    const cache = new WeComViewerMessageCache({ maxEntries: 4, ttlMs: 60_000, now: () => 100 });

    cache.setMany('token-a', 'wecom:contact', [
      { msgid: 'm1', secretKey: 's1' },
      { msgid: 'm2', secretKey: 's2' },
    ]);
    cache.set('token-b', 'wecom:contact', { msgid: 'm3', secretKey: 's3' });

    cache.clearToken('token-a');

    expect(cache.get('token-a', 'wecom:contact', 'm1')).toBeUndefined();
    expect(cache.get('token-b', 'wecom:contact', 'm3')).toEqual({ msgid: 'm3', secretKey: 's3' });
    expect(cache.size()).toBe(1);
  });
});
