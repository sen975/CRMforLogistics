import { beforeEach, describe, expect, it, vi } from 'vitest';
import client from './client';
import {
  fetchWeComInstallations,
  fetchWeComAppChat,
  listWeComDirectoryMembers,
} from './endpoints';

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn() }));
vi.mock('./client', () => ({ default: http }));

beforeEach(() => vi.clearAllMocks());

describe('WeCom P0 endpoints', () => {
  it('uses encoded authCorpId/chatId paths and never declares credentials', async () => {
    http.get.mockResolvedValue({ data: [] });
    await fetchWeComInstallations();
    await fetchWeComAppChat('corp/a', 'chat/1');
    expect(http.get).toHaveBeenNthCalledWith(1, '/v1/wecom/installations');
    expect(http.get).toHaveBeenNthCalledWith(2, '/v1/wecom/installations/corp%2Fa/app-chats/chat%2F1');
    expect(Object.keys(fetchWeComInstallations as unknown as object)).not.toContain('accessToken');
  });

  it('passes directory filters through query parameters', async () => {
    http.get.mockResolvedValue({ data: {} });
    await listWeComDirectoryMembers('corp-1', 7, true);
    expect(http.get).toHaveBeenCalledWith('/v1/wecom/installations/corp-1/directory/members', {
      params: { departmentId: 7, fetchChild: true },
    });
  });
});
