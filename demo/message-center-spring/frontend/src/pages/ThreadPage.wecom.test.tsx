import '@testing-library/jest-dom/vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import type { MessageResponse } from '../api/types';
import ThreadPage from './ThreadPage';

const api = vi.hoisted(() => ({
  fetchThread: vi.fn(),
  fetchWeComContactThread: vi.fn(),
  fetchContact: vi.fn(),
  markContactRead: vi.fn(),
  selectChannel: vi.fn(),
  renderWeComConversation: vi.fn(),
  refreshWeComContactThread: vi.fn(),
  sse: vi.fn(),
}));

vi.mock('../api/endpoints', () => api);
vi.mock('../hooks/useSse', () => ({
  useSse: (callback: () => void) => {
    api.sse.mockImplementation(callback);
  },
}));
vi.mock('../hooks/useCallRecordTimeline', () => ({
  useCallRecordTimeline: () => ({ records: [], refresh: vi.fn() }),
}));
vi.mock('../hooks/useDetailPanel', async () => {
  const React = await vi.importActual<typeof import('react')>('react');

  return {
    useDetailPanel: () => {
      const [selectedChannel, setSelectedChannel] = React.useState<string | null>(null);
      return {
        selectedDetail: null,
        selectMessage: vi.fn(),
        selectCallRecord: vi.fn(),
        clearSelection: vi.fn(),
        selectedChannel,
        selectChannel: (channel: string) => {
          api.selectChannel(channel);
          setSelectedChannel(channel);
        },
        clearChannel: () => setSelectedChannel(null),
      };
    },
  };
});
vi.mock('../hooks/useWeComViewer', () => ({
  useWeComViewer: () => ({ prepareSegment: vi.fn(), reportComponentError: vi.fn() }),
}));
vi.mock('../components/SendForm', () => ({
  default: ({
    activeChannel,
    onChannelChange,
    selectedIdentityId,
  }: {
    activeChannel?: string;
    onChannelChange?: (channel: string) => void;
    selectedIdentityId?: string;
  }) => (
    <div aria-label="发送渠道">
      <span>ChatApp</span>
      <span>企业微信CorpId</span>
      <button type="button" role="tab" aria-selected={activeChannel === 'wecom'} onClick={() => onChannelChange?.('wecom')}>企业微信</button>
      <button type="button" role="tab" aria-selected={activeChannel === 'email'} onClick={() => onChannelChange?.('email')}>邮件</button>
      <button type="button" role="tab" aria-selected={activeChannel === 'call'} onClick={() => onChannelChange?.('call')}>电话记录</button>
      <span data-testid="selected-identity">{selectedIdentityId ?? ''}</span>
    </div>
  ),
}));
vi.mock('../components/MessageBubble', () => ({
  default: ({ message }: { message: MessageResponse }) => (
    <div data-testid={`message-${message.id}`}>{message.bodyText}</div>
  ),
}));
vi.mock('../components/wecom/WeComTimelineSegment', () => ({
  WeComTimelineSegment: ({ segment }: { segment: { id: string; items: MessageResponse[] } }) => (
    <div data-testid={`wecom-${segment.id}`}>wecom:{segment.items.length}</div>
  ),
}));
vi.mock('../components/wecom/WeComConversationPanel', () => ({
  WeComConversationPanel: ({ contactPointId, items, relatedGroups, onOpenGroup, onSwitchToMixed }: {
    contactPointId: string;
    items: MessageResponse[];
    relatedGroups?: Array<{ sourceConversationId: string }>;
    onOpenGroup?: (sourceConversationId: string) => void;
    onSwitchToMixed?: () => void;
  }) => {
    api.renderWeComConversation(contactPointId, items.map((item) => item.id));
    return <div data-testid="wecom-conversation-page">
      整页企业微信:{items.length}
      {onSwitchToMixed ? <button type="button" onClick={onSwitchToMixed}>切换</button> : null}
      {relatedGroups?.map((group) => (
        <button key={group.sourceConversationId} onClick={() => onOpenGroup?.(group.sourceConversationId)}>
          关联群:{group.sourceConversationId}
        </button>
      ))}
    </div>;
  },
}));

function message(id: string, channelType: string): MessageResponse {
  return {
    id,
    sourceId: `${channelType}-${id}`,
    direction: 'inbound',
    kind: 'text',
    subject: '',
    bodyText: channelType === 'wecom' ? '' : id,
    bodyHtml: '',
    channelType,
    from: 'sender',
    to: 'receiver',
    occurredAt: `2026-08-17T00:00:0${id}Z`,
    status: 'received',
    ingestSequence: Number(id),
    attachments: [],
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((nextResolve, nextReject) => {
    resolve = nextResolve;
    reject = nextReject;
  });
  return { promise, resolve, reject };
}

function ContactNavigation() {
  const navigate = useNavigate();
  return (
    <>
      <button type="button" onClick={() => navigate('/thread/contact-a')}>联系人 A</button>
      <button type="button" onClick={() => navigate('/thread/contact-b')}>联系人 B</button>
    </>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchContact.mockResolvedValue({
    id: 'contact-1',
    displayName: '客户一',
    remark: '',
    channelTypes: ['wecom', 'email'],
    lastMessageAt: null,
    lastText: '',
    messageCount: 3,
    unreadCount: 0,
    identities: [{
      id: 'identity-1',
      channelType: 'wecom',
      identityScope: 'external',
      identityValue: 'external-1',
      displayName: '客户一',
    }],
  });
  api.fetchThread.mockResolvedValue({
    items: [message('1', 'wecom'), message('2', 'email'), message('3', 'wecom')],
    nextCursor: '',
    messageCount: 3,
    threadRevision: 'revision-1',
  });
  api.fetchWeComContactThread.mockResolvedValue({
    contactId: 'contact-1',
    sourceConversationIds: [],
    relatedGroups: [],
    items: [message('1', 'wecom'), message('3', 'wecom')],
    nextCursor: null,
    messageCount: 2,
    threadRevision: 'wecom-revision-1',
  });
  api.markContactRead.mockResolvedValue(undefined);
});

it('switches the merged contact to a full-page WeCom conversation and returns to the timeline', async () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/thread/contact-1']}>
        <Routes>
          <Route path="/thread/:contactId" element={<ThreadPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );

  await waitFor(() => expect(screen.getByTestId('message-2')).toBeInTheDocument());
  expect(screen.queryByTestId('message-1')).not.toBeInTheDocument();
  expect(screen.queryByTestId('message-3')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '打开企业微信会话' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('tab', { name: '企业微信' }));
  expect(api.selectChannel).toHaveBeenLastCalledWith('wecom');
  await waitFor(() => {
    expect(screen.getByTestId('wecom-conversation-page')).toHaveTextContent('整页企业微信:2');
  });
  expect(screen.queryByLabelText('发送渠道')).not.toBeInTheDocument();
  expect(screen.queryByText('ChatApp')).not.toBeInTheDocument();
  expect(screen.queryByText('企业微信CorpId')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '切换' }));
  await waitFor(() => expect(screen.getByTestId('thread-timeline')).toBeInTheDocument());
  expect(screen.getByTestId('message-2')).toBeInTheDocument();
});

it('opens a phone address-book timeline in read-only mode', async () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/conversations/contact/contact-1?channel=phone&identityId=phone-1']}>
        <Routes>
          <Route path="/conversations/contact/:contactId" element={<ThreadPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );

  await waitFor(() => expect(screen.getByTestId('thread-timeline')).toBeInTheDocument());
  expect(screen.queryByLabelText('发送渠道')).not.toBeInTheDocument();
});

it('passes the address-book-selected identity into the matching send channel', async () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/conversations/contact/contact-1?channel=email&identityId=email-2']}>
        <Routes>
          <Route path="/conversations/contact/:contactId" element={<ThreadPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );

  await waitFor(() => expect(screen.getByTestId('selected-identity')).toHaveTextContent('email-2'));
});

it('opens the full-page WeCom conversation automatically for a WeCom-only contact', async () => {
  api.fetchContact.mockResolvedValueOnce({
    id: 'contact-1',
    displayName: '客户一',
    remark: '',
    channelTypes: ['wecom'],
    lastMessageAt: null,
    lastText: '',
    messageCount: 2,
    unreadCount: 0,
    identities: [{
      id: 'identity-1',
      channelType: 'wecom',
      identityScope: 'external',
      identityValue: 'external-1',
      displayName: '客户一',
    }],
  });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/thread/contact-1']}>
        <Routes>
          <Route path="/thread/:contactId" element={<ThreadPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );

  await waitFor(() => {
    expect(screen.getByTestId('wecom-conversation-page')).toHaveTextContent('整页企业微信:2');
  });
  expect(api.selectChannel).toHaveBeenCalledWith('wecom');
  expect(screen.queryByTestId('thread-timeline')).not.toBeInTheDocument();
  expect(screen.queryByLabelText('发送渠道')).not.toBeInTheDocument();
});

it('renders only direct messages and exposes related groups as independent navigation', async () => {
  const direct = { ...message('direct', 'wecom'), conversationType: 'DIRECT' as const };
  const grouped = { ...message('grouped', 'wecom'), conversationType: 'GROUP' as const };
  api.fetchThread.mockResolvedValueOnce({
    items: [direct, grouped],
    nextCursor: '',
    messageCount: 2,
    threadRevision: 'legacy-mixed',
  });
  api.fetchWeComContactThread.mockResolvedValueOnce({
    contactId: 'contact-1',
    sourceConversationIds: ['direct-source'],
    relatedGroups: [{
      sourceConversationId: 'group-source',
      displayName: '客户项目群',
      avatarUrl: null,
      participantCount: 3,
    }],
    items: [direct],
    nextCursor: null,
    messageCount: 1,
    threadRevision: 'direct-only',
  });
  api.fetchContact.mockResolvedValueOnce({
    id: 'contact-1', displayName: '客户一', remark: '', channelTypes: ['wecom'],
    lastMessageAt: null, lastText: '', messageCount: 2, unreadCount: 0,
    identities: [{ id: 'identity-1', channelType: 'wecom', identityScope: 'external', identityValue: 'external-1', displayName: '客户一' }],
  });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/thread/contact-1']}>
        <Routes>
          <Route path="/thread/:contactId" element={<ThreadPage />} />
          <Route path="/conversations/wecom-group/:sourceConversationId" element={<div>独立群聊页面</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );

  await waitFor(() => expect(api.renderWeComConversation).toHaveBeenCalledWith(
    'wecom:external-1',
    ['direct'],
  ));
  expect(api.renderWeComConversation).not.toHaveBeenCalledWith(
    'wecom:external-1',
    expect.arrayContaining(['grouped']),
  );
  fireEvent.click(screen.getByRole('button', { name: '关联群:group-source' }));
  expect(await screen.findByText('独立群聊页面')).toBeInTheDocument();
});

it('refreshes the dedicated direct WeCom query from the thread refresh control', async () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/thread/contact-1']}>
        <Routes>
          <Route path="/thread/:contactId" element={<ThreadPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );

  await waitFor(() => expect(api.fetchWeComContactThread).toHaveBeenCalledTimes(1));
  fireEvent.click(screen.getByRole('button', { name: '刷新联系人会话' }));
  await waitFor(() => expect(api.fetchWeComContactThread).toHaveBeenCalledTimes(2));
});

it('invalidates the dedicated direct WeCom query when an SSE message arrives', async () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries');
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/thread/contact-1']}>
        <Routes>
          <Route path="/thread/:contactId" element={<ThreadPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );

  await waitFor(() => expect(api.fetchWeComContactThread).toHaveBeenCalledTimes(1));
  await act(async () => {
    api.sse();
  });
  expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['wecom-contact-thread', 'contact-1'] });
});

it('ignores a delayed A response after A to B to A contact navigation', async () => {
  const firstA = deferred<Awaited<ReturnType<typeof api.fetchThread>>>();
  const threadB = deferred<Awaited<ReturnType<typeof api.fetchThread>>>();
  const finalA = deferred<Awaited<ReturnType<typeof api.fetchThread>>>();
  api.fetchContact.mockImplementation((contactId: string) => Promise.resolve({
    id: contactId,
    displayName: contactId,
    remark: '',
    channelTypes: ['email'],
    lastMessageAt: null,
    lastText: '',
    messageCount: 1,
    unreadCount: 0,
    identities: [],
  }));
  api.fetchThread
    .mockImplementationOnce(() => firstA.promise)
    .mockImplementationOnce(() => threadB.promise)
    .mockImplementationOnce(() => finalA.promise);
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/thread/contact-a']}>
        <ContactNavigation />
        <Routes>
          <Route path="/thread/:contactId" element={<ThreadPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );

  await waitFor(() => expect(api.fetchThread).toHaveBeenCalledTimes(1));
  fireEvent.click(screen.getByRole('button', { name: '联系人 B' }));
  await waitFor(() => expect(api.fetchThread).toHaveBeenCalledTimes(2));
  await act(async () => {
    threadB.resolve({ items: [message('b-current', 'email')], nextCursor: '', messageCount: 1, threadRevision: 'b' });
  });
  await waitFor(() => expect(screen.getByTestId('message-b-current')).toBeInTheDocument());

  await act(async () => {
    firstA.resolve({ items: [message('a-stale', 'email')], nextCursor: '', messageCount: 1, threadRevision: 'a-old' });
  });
  expect(screen.queryByTestId('message-a-stale')).not.toBeInTheDocument();
  expect(screen.getByTestId('message-b-current')).toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: '联系人 A' }));
  await waitFor(() => expect(api.fetchThread).toHaveBeenCalledTimes(3));
  await act(async () => {
    finalA.resolve({ items: [message('a-current', 'email')], nextCursor: '', messageCount: 1, threadRevision: 'a-new' });
  });
  await waitFor(() => expect(screen.getByTestId('message-a-current')).toBeInTheDocument());
  expect(screen.queryByTestId('message-b-current')).not.toBeInTheDocument();
  expect(screen.queryByTestId('message-a-stale')).not.toBeInTheDocument();
});

it('never pairs the new contact point with messages retained from the previous contact', async () => {
  const threadB = deferred<Awaited<ReturnType<typeof api.fetchThread>>>();
  api.fetchContact.mockImplementation((contactId: string) => Promise.resolve({
    id: contactId,
    displayName: contactId,
    remark: '',
    channelTypes: ['wecom'],
    lastMessageAt: null,
    lastText: '',
    messageCount: 1,
    unreadCount: 0,
    identities: [{
      id: `identity-${contactId}`,
      channelType: 'wecom',
      identityScope: 'external',
      identityValue: `external-${contactId}`,
      displayName: contactId,
    }],
  }));
  api.fetchThread
    .mockResolvedValueOnce({
      items: [message('a-message', 'wecom')],
      nextCursor: '',
      messageCount: 1,
      threadRevision: 'a',
    })
    .mockImplementationOnce(() => threadB.promise);
  api.fetchWeComContactThread.mockImplementation((contactId: string) => Promise.resolve({
    contactId,
    sourceConversationIds: [`source-${contactId}`],
    relatedGroups: [],
    items: [message(contactId === 'contact-a' ? 'a-message' : 'b-message', 'wecom')],
    nextCursor: null,
    messageCount: 1,
    threadRevision: contactId,
  }));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(['contact', 'contact-b'], {
    id: 'contact-b',
    displayName: 'contact-b',
    remark: '',
    channelTypes: ['wecom'],
    lastMessageAt: null,
    lastText: '',
    messageCount: 1,
    unreadCount: 0,
    identities: [{
      id: 'identity-contact-b',
      channelType: 'wecom',
      identityScope: 'external',
      identityValue: 'external-contact-b',
      displayName: 'contact-b',
    }],
  });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/thread/contact-a']}>
        <ContactNavigation />
        <Routes>
          <Route path="/thread/:contactId" element={<ThreadPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );

  await waitFor(() => expect(api.renderWeComConversation).toHaveBeenCalledWith(
    'wecom:external-contact-a',
    ['a-message'],
  ));
  api.renderWeComConversation.mockClear();

  fireEvent.click(screen.getByRole('button', { name: '联系人 B' }));
  await waitFor(() => expect(api.fetchThread).toHaveBeenCalledTimes(2));
  await waitFor(() => expect(api.fetchContact).toHaveBeenCalledWith('contact-b'));

  expect(api.renderWeComConversation).not.toHaveBeenCalledWith(
    'wecom:external-contact-b',
    ['a-message'],
  );

  await act(async () => {
    threadB.resolve({
      items: [message('b-message', 'wecom')],
      nextCursor: '',
      messageCount: 1,
      threadRevision: 'b',
    });
  });
  await waitFor(() => expect(api.renderWeComConversation).toHaveBeenCalledWith(
    'wecom:external-contact-b',
    ['b-message'],
  ));
});

it('does not invalidate contact lists when an old contact read mutation completes', async () => {
  const oldARead = deferred<void>();
  api.fetchContact.mockImplementation((contactId: string) => Promise.resolve({
    id: contactId,
    displayName: contactId,
    remark: '',
    channelTypes: ['email'],
    lastMessageAt: null,
    lastText: '',
    messageCount: 1,
    unreadCount: 0,
    identities: [],
  }));
  api.fetchThread.mockImplementation((contactId: string) => Promise.resolve({
    items: [message(`${contactId}-message`, 'email')],
    nextCursor: '',
    messageCount: 1,
    threadRevision: contactId,
  }));
  api.markContactRead
    .mockImplementationOnce(() => oldARead.promise)
    .mockResolvedValue(undefined);
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries');
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/thread/contact-a']}>
        <ContactNavigation />
        <Routes>
          <Route path="/thread/:contactId" element={<ThreadPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );

  await waitFor(() => expect(api.markContactRead).toHaveBeenCalledWith('contact-a'));
  fireEvent.click(screen.getByRole('button', { name: '联系人 B' }));
  await waitFor(() => expect(screen.getByTestId('message-contact-b-message')).toBeInTheDocument());
  await waitFor(() => expect(api.markContactRead).toHaveBeenCalledWith('contact-b'));
  invalidateQueries.mockClear();

  await act(async () => {
    oldARead.resolve();
  });

  expect(invalidateQueries).not.toHaveBeenCalled();
  expect(screen.getByTestId('message-contact-b-message')).toBeInTheDocument();
});
