import { beforeEach, describe, expect, it, vi } from 'vitest';
import client from './client';
import { fetchContact } from './endpoints';
import type { ContactResponse } from './types';

const http = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('./client', () => ({ default: http }));

describe('contact memory response contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('preserves the server memory projection returned with contact details', async () => {
    const response: ContactResponse = {
      id: 'contact-1',
      displayName: '客户一',
      remark: '',
      channelTypes: ['email'],
      lastMessageAt: null,
      lastText: '',
      messageCount: 2,
      unreadCount: 0,
      tags: [{ id: 'manual-1', name: '人工VIP', color: 'gold' }],
      identities: [],
      memory: {
        profile: {
          id: 'profile-1',
          version: 2,
          content: '客户关注海运时效。',
          createdAt: '2026-09-11T00:00:00Z',
        },
        humanTags: [{ id: 'manual-1', name: '人工VIP', color: 'gold' }],
        aiTags: [{
          id: 'ai-1',
          name: '海运客户',
          category: 'PRODUCT_INTEREST',
          colorToken: 'green',
          status: 'STALE',
          confidence: 0.91,
        }],
        state: 'PROCESSING',
        lastSuccessAt: '2026-09-10T00:00:00Z',
        lastFailureCode: null,
        pendingInbound: true,
      },
    };
    http.get.mockResolvedValue({ data: response });

    const result = await fetchContact('contact-1');

    expect(http.get).toHaveBeenCalledWith('/contacts/contact-1');
    expect(result.memory?.profile?.content).toBe('客户关注海运时效。');
    expect(result.memory?.aiTags[0]).toMatchObject({
      name: '海运客户',
      colorToken: 'green',
      status: 'STALE',
    });
  });
});
