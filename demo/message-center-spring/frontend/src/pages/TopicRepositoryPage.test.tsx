import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import TopicRepositoryPage from './TopicRepositoryPage';

vi.mock('../hooks/useTopicRepository', () => ({
  useTopicRepository: () => ({
    data: {
      records: [{
        id: 'topic-1', contactId: 'contact-42', title: '报价确认', summary: '归档摘要',
        summarySource: 'AI', firstOccurredAt: '2026-08-01T00:00:00Z', lastOccurredAt: '2026-08-02T00:00:00Z',
        channels: ['email'], sourceCount: 1, sourceItems: [], version: 1,
        contactName: '', contactRemark: '', contactChannelType: 'email', contactChannelNickname: '张三邮箱',
      }], total: 1,
    }, isLoading: false, restore: { isPending: false, mutate: vi.fn() }, requests: { data: [] }, approve: { mutate: vi.fn() }, reject: { mutate: vi.fn() },
  }),
}));

describe('TopicRepositoryPage', () => {
  it('shows the owning contact and event time for archived topics', () => {
    render(<TopicRepositoryPage />);
    expect(screen.getByText(content => content.includes('归属联系人：') && content.includes('邮件') && content.includes('张三邮箱'))).toBeInTheDocument();
    expect(screen.getByText(/事件时间：/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '恢复' })).toBeInTheDocument();
  });
});
