import '@testing-library/jest-dom/vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import ConversationWorkspace from './ConversationWorkspace';

const api = vi.hoisted(() => ({
  fetchWeComGroupThread: vi.fn(),
  fetchWeComGroupTopics: vi.fn(),
}));
const sse = vi.hoisted(() => ({ useSse: vi.fn() }));

vi.mock('../api/endpoints', () => api);
vi.mock('../hooks/useSse', () => sse);
vi.mock('../hooks/useWeComViewer', () => ({ useWeComViewer: () => ({}) }));
vi.mock('../components/wecom/WeComConversationPanel', () => ({
  WeComConversationPanel: () => <div data-testid="wecom-group-messages" />,
}));

beforeEach(() => {
  api.fetchWeComGroupThread.mockResolvedValue({
    sourceConversationId: 'group-1',
    groupChatId: 'wrNTkcAAAJuUdnO3GEr6qeCT',
    displayName: '报价项目群',
    avatarUrl: null,
    openClientUrl: null,
    participants: [],
    items: [],
    nextCursor: null,
    messageCount: 0,
    threadRevision: 'revision-1',
  });
  api.fetchWeComGroupTopics.mockResolvedValue({
    sourceConversationId: 'group-1',
    generation: { status: 'NOT_STARTED', jobId: null, errorCode: null, updatedAt: null },
    topics: [],
    weComUnsupported: false,
  });
  sse.useSse.mockReset();
});

it('leaves the group Topic panel to the shared application detail sider', async () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/conversations/wecom-group/group-1']}>
        <Routes>
          <Route path="/conversations/wecom-group/:sourceConversationId" element={<ConversationWorkspace />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );

  await waitFor(() => expect(screen.getByTestId('wecom-group-workspace')).toBeInTheDocument());
  expect(screen.queryByText('报价项目群')).not.toBeInTheDocument();
  expect(screen.queryByText('0 位成员')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '查看群 Topic' })).not.toBeInTheDocument();
  expect(screen.queryByTestId('group-topic-panel')).not.toBeInTheDocument();
  expect(api.fetchWeComGroupTopics).not.toHaveBeenCalled();
});

it('refetches the open group once after the asynchronous group-kind projection completes', async () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries');
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/conversations/wecom-group/group-1']}>
        <Routes>
          <Route path="/conversations/wecom-group/:sourceConversationId" element={<ConversationWorkspace />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  await screen.findByTestId('wecom-group-workspace');

  const call = sse.useSse.mock.calls.find(([, events]) =>
    Array.isArray(events) && events.includes('wecom-group-kind-sync-completed'));
  expect(call).toBeDefined();
  const onCompleted = call?.[0] as (event: { type: string; data: unknown }) => void;
  onCompleted({ type: 'wecom-group-kind-sync-completed', data: {} });

  await waitFor(() => expect(invalidateQueries)
    .toHaveBeenCalledWith({ queryKey: ['wecom-group-thread', 'group-1'] }));
});
