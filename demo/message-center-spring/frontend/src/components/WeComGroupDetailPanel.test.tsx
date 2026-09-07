import '@testing-library/jest-dom/vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import WeComGroupDetailPanel from './WeComGroupDetailPanel';

const api = vi.hoisted(() => ({
  fetchWeComGroupThread: vi.fn(),
  fetchWeComGroupTopics: vi.fn(),
  refreshWeComGroupName: vi.fn(),
}));
const sse = vi.hoisted(() => ({ useSse: vi.fn() }));

vi.mock('../api/endpoints', () => api);
vi.mock('../hooks/useSse', () => sse);
vi.mock('./AiTopicTimeline', () => ({
  default: () => <div data-testid="group-topic-timeline">Topic 时间轴内容</div>,
}));

describe('WeComGroupDetailPanel', () => {
  beforeEach(() => {
    sse.useSse.mockReset();
    api.fetchWeComGroupThread.mockResolvedValue({
      sourceConversationId: 'group-1',
      groupChatId: 'chat-1',
      displayName: '不应在右侧栏显示的群名',
      avatarUrl: null,
      openClientUrl: null,
      participants: [
        {
          partyId: 'party-1',
          partyType: 'EMPLOYEE',
          providerPartyId: 'employee-1',
          displayName: '张三',
          avatarUrl: null,
          contactId: null,
          contactAccessible: false,
          isCurrentViewer: true,
        },
      ],
      items: [],
      nextCursor: null,
      messageCount: 0,
      threadRevision: '1',
    });
    api.fetchWeComGroupTopics.mockResolvedValue({
      topics: [],
      generation: { status: 'READY' },
      weComUnsupported: false,
    });
    api.refreshWeComGroupName.mockResolvedValue({
      id: 'refresh-1',
      sourceConversationId: 'group-1',
      triggerSource: 'MANUAL',
      status: 'PENDING',
      createdAt: '2026-09-03T00:00:00Z',
    });
  });

  function renderPanel() {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/conversations/wecom-group/group-1']}>
          <Routes>
            <Route path="/conversations/wecom-group/:sourceConversationId" element={<WeComGroupDetailPanel />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
  }

  it('switches between Topic and members tabs without displaying the group name', async () => {
    renderPanel();
    expect(await screen.findByTestId('group-topic-timeline')).toBeInTheDocument();
    expect(screen.queryByText('不应在右侧栏显示的群名')).not.toBeInTheDocument();

    await userEvent.setup().click(screen.getByRole('tab', { name: '群成员' }));
    expect(screen.getByText('张三')).toBeInTheDocument();
    expect(screen.queryByTestId('group-topic-timeline')).not.toBeInTheDocument();

    await userEvent.setup().click(screen.getByRole('tab', { name: 'Topic 时间轴' }));
    expect(screen.getByTestId('group-topic-timeline')).toBeInTheDocument();
  });

  it('keeps the manual group-name refresh action in the members tab', async () => {
    renderPanel();
    const user = userEvent.setup();

    await user.click(screen.getByRole('tab', { name: '群成员' }));
    await screen.findByText('张三');
    await user.click(screen.getByRole('button', { name: '刷新群昵称' }));

    expect(api.refreshWeComGroupName).toHaveBeenCalledWith('group-1');
    expect(screen.getByRole('button', { name: '刷新群昵称' })).toBeDisabled();

    const call = sse.useSse.mock.calls.find(([, events]) =>
      Array.isArray(events) && events.includes('wecom-group-name-refresh-completed'));
    const onCompleted = call?.[0] as ((event: { data: { sourceConversationId: string } }) => void) | undefined;
    onCompleted?.({ data: { sourceConversationId: 'group-1' } });
  });

  it('uses compact tabs that fit the fixed right sidebar', async () => {
    renderPanel();
    const tabs = await screen.findByTestId('wecom-group-detail-tabs');
    expect(tabs).toHaveClass('ant-tabs-small');
    expect(tabs).toHaveStyle({ width: '100%', height: '100%' });
  });
});
