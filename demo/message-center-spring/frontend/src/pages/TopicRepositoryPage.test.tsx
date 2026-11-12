import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import TopicRepositoryPage from './TopicRepositoryPage';

let records: unknown[] = [];

afterEach(() => {
  window.history.replaceState({}, '', '/');
});

vi.mock('../hooks/useTopicRepository', () => ({
  useTopicRepository: () => ({
    data: {
      records, total: records.length,
    }, isLoading: false, restore: { isPending: false, mutate: vi.fn() }, requests: { data: [] }, approve: { mutate: vi.fn() }, reject: { mutate: vi.fn() },
  }),
}));

describe('TopicRepositoryPage', () => {
  it('shows the owning contact and event time for archived topics', () => {
    records = [{
      id: 'topic-1', contactId: 'contact-42', title: '报价确认', summary: '归档摘要',
      summarySource: 'AI', firstOccurredAt: '2026-08-01T00:00:00Z', lastOccurredAt: '2026-08-02T00:00:00Z',
      channels: ['email'], sourceCount: 1, sourceItems: [], version: 1,
      contactName: '', contactRemark: '', contactChannelType: 'email', contactChannelNickname: '张三邮箱',
    }];
    render(<TopicRepositoryPage />);
    expect(screen.getByText(content => content.includes('归属联系人：') && content.includes('邮件') && content.includes('张三邮箱'))).toBeInTheDocument();
    expect(screen.getByText(/事件时间：/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '恢复' })).toBeInTheDocument();
  });

  it('shows the safe internal-group label without a raw conversation key', () => {
    records = [{
      id: 'topic-2', title: '内部项目排期', summary: '已归档',
      summarySource: 'AI', firstOccurredAt: '2026-08-01T00:00:00Z', lastOccurredAt: '2026-08-02T00:00:00Z',
      channels: ['wecom'], sourceCount: 1, sourceItems: [], version: 1,
      ownerType: 'WECOM_GROUP', ownerLabel: '内部群聊', ownerId: 'group-42',
    }];
    render(<TopicRepositoryPage />);

    expect(screen.getByText(content => content.includes('归属群：内部群聊'))).toBeInTheDocument();
    expect(screen.queryByText(/group:/)).not.toBeInTheDocument();
  });

  it('replaces legacy raw group keys in stored and pending Topic ownership labels', () => {
    records = [{
      id: 'topic-3', title: '群报价', summary: '已归档', summarySource: 'AI',
      firstOccurredAt: '2026-08-01T00:00:00Z', lastOccurredAt: '2026-08-02T00:00:00Z',
      channels: ['wecom'], sourceCount: 1, sourceItems: [], version: 1,
      ownerType: 'WECOM_GROUP', ownerLabel: 'group:raw-chat-id', ownerId: 'group-43',
    }];
    render(<TopicRepositoryPage />);

    expect(screen.getByText(content => content.includes('归属群：外部群聊'))).toBeInTheDocument();
    expect(screen.queryByText(/group:/)).not.toBeInTheDocument();
  });

  it('uses a Topic search query when opened from a locked source record', () => {
    window.history.replaceState({}, '', '/topic-repository?search=华东提单跟进');
    render(<TopicRepositoryPage />);
    expect(screen.getByPlaceholderText('搜索联系人或 Topic')).toHaveValue('华东提单跟进');
  });
});
