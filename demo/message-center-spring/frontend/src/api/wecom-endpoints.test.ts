import { beforeEach, describe, expect, it, vi } from 'vitest';
import client from './client';
import {
  fetchWeComInstallations,
  fetchWeComAppChat,
  listWeComDirectoryMembers,
  createWeComViewerTargetSession,
  previewTopicFusion,
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

  it('does not send frame-only group chat metadata to the strict viewer-session contract', async () => {
    http.post.mockResolvedValue({ data: { viewerSessionId: 'session-1', expiresIn: 60 } });
    await createWeComViewerTargetSession(
      { targetType: 'WECOM_GROUP', targetId: 'group-1', chatId: 'chat-1' } as any,
      ['message-1'],
      'viewer-token',
    );
    expect(http.post).toHaveBeenCalledWith(
      '/v1/wecom/conversation-view/sessions',
      { targetType: 'WECOM_GROUP', targetId: 'group-1', messageIds: ['message-1'] },
      { headers: { 'X-WeCom-Viewer-Token': 'viewer-token' }, signal: undefined },
    );
  });

  it('sends only fusion fields accepted by the strict preview contract', async () => {
    http.post.mockResolvedValue({ data: {} });

    const legacyMergePayload = {
      contactId: 'contact-1',
      topicIds: ['topic-1', 'topic-2'],
      expectedVersions: { 'topic-1': 3, 'topic-2': 3 },
    };
    await previewTopicFusion('contact-1', legacyMergePayload);

    expect(http.post).toHaveBeenCalledWith(
      '/v1/contacts/contact-1/topic-fusion/preview',
      {
        topicIds: ['topic-1', 'topic-2'],
        expectedVersions: { 'topic-1': 3, 'topic-2': 3 },
      },
    );
  });
});
