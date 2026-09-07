import '@testing-library/jest-dom/vitest';
import { createEvent, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { message } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ContactsPage from './ContactsPage';

const hooks = vi.hoisted(() => ({
  useUnifiedConversations: vi.fn(),
  useConversationPreference: vi.fn(),
  useMergeContacts: vi.fn(),
  useSse: vi.fn(),
}));

vi.mock('../hooks/useContacts', () => hooks);
vi.mock('../hooks/useSse', () => ({ useSse: hooks.useSse }));

beforeEach(() => {
  vi.clearAllMocks();
  hooks.useUnifiedConversations.mockReturnValue({ data: { records: [
    { type: 'CONTACT', id: 'c1', displayName: '客户甲', channelTypes: ['email'], lastMessageAt: null, lastText: '', messageCount: 0, unreadCount: 0, pinned: false },
    { type: 'CONTACT', id: 'c2', displayName: '客户乙', channelTypes: ['email'], lastMessageAt: null, lastText: '', messageCount: 0, unreadCount: 0, pinned: false },
  ], total: 2 }, isLoading: false });
  hooks.useMergeContacts.mockReturnValue({ mutateAsync: vi.fn().mockResolvedValue(undefined), isPending: false });
  hooks.useConversationPreference.mockReturnValue({ mutateAsync: vi.fn().mockResolvedValue(undefined), isPending: false });
});

function dataTransfer() {
  const values = new Map<string, string>();
  return {
    effectAllowed: 'none',
    dropEffect: 'none',
    setData: (type: string, value: string) => values.set(type, value),
    getData: (type: string) => values.get(type) ?? '',
  };
}

function dragOverAt(element: HTMLElement, transfer: ReturnType<typeof dataTransfer>, clientY: number) {
  const event = createEvent.dragOver(element, { dataTransfer: transfer });
  Object.defineProperty(event, 'clientY', { value: clientY });
  fireEvent(element, event);
}

describe('ContactsPage conversation actions', () => {
  it('shows only pin and delete in the context menu', async () => {
    render(<QueryClientProvider client={new QueryClient()}><MemoryRouter><ContactsPage /></MemoryRouter></QueryClientProvider>);
    // Context menu is intentionally exercised on the row rather than a card action.
    fireEvent.contextMenu(screen.getByText('客户甲'));
    expect(await screen.findByText('置顶')).toBeInTheDocument();
    expect(screen.getByText('删除')).toBeInTheDocument();
    expect(screen.queryByText('标为未读')).not.toBeInTheDocument();
  });

  it('executes pin and delete only after the matching menu item is selected', async () => {
    const preference = vi.fn().mockResolvedValue(undefined);
    hooks.useConversationPreference.mockReturnValue({ mutateAsync: preference, isPending: false });
    render(<QueryClientProvider client={new QueryClient()}><MemoryRouter><ContactsPage /></MemoryRouter></QueryClientProvider>);

    fireEvent.contextMenu(screen.getByText('客户甲'));
    fireEvent.click(await screen.findByText('置顶'));
    await waitFor(() => expect(preference).toHaveBeenCalledWith({ action: 'pin', targetType: 'CONTACT', targetId: 'c1' }));

    fireEvent.contextMenu(screen.getByText('客户甲'));
    fireEvent.click(await screen.findByText('删除'));
    await waitFor(() => expect(preference).toHaveBeenCalledWith({ action: 'delete', targetType: 'CONTACT', targetId: 'c1' }));
  });

  it('reorders when dropped into the gap before another conversation', async () => {
    const preference = vi.fn().mockResolvedValue(undefined);
    hooks.useConversationPreference.mockReturnValue({ mutateAsync: preference, isPending: false });
    const successMessage = vi.spyOn(message, 'success');
    render(<QueryClientProvider client={new QueryClient()}><MemoryRouter><ContactsPage /></MemoryRouter></QueryClientProvider>);
    const source = screen.getByTestId('conversation-c2');
    const target = screen.getByTestId('conversation-c1');
    Object.defineProperty(target, 'getBoundingClientRect', {
      value: () => ({ top: 0, bottom: 100, left: 0, right: 200, width: 200, height: 100, x: 0, y: 0, toJSON: () => ({}) }),
    });
    const transfer = dataTransfer();

    fireEvent.dragStart(source, { dataTransfer: transfer });
    dragOverAt(target, transfer, 10);
    expect(target).toHaveAttribute('data-drop-mode', 'before');
    fireEvent.drop(target, { dataTransfer: transfer, clientY: 10 });

    await waitFor(() => expect(preference).toHaveBeenCalledWith({
      action: 'order',
      sourceType: 'CONTACT',
      sourceId: 'c2',
      targetType: 'CONTACT',
      targetId: 'c1',
      placement: 'BEFORE',
    }));
    expect(successMessage).not.toHaveBeenCalled();
    expect(hooks.useMergeContacts().mutateAsync).not.toHaveBeenCalled();
  });

  it('uses only the gap insertion line while ordering', () => {
    render(<QueryClientProvider client={new QueryClient()}><MemoryRouter><ContactsPage /></MemoryRouter></QueryClientProvider>);
    const source = screen.getByTestId('conversation-c2');
    const target = screen.getByTestId('conversation-c1');
    Object.defineProperty(target, 'getBoundingClientRect', {
      value: () => ({ top: 0, bottom: 100, left: 0, right: 200, width: 200, height: 100, x: 0, y: 0, toJSON: () => ({}) }),
    });
    const transfer = dataTransfer();

    fireEvent.dragStart(source, { dataTransfer: transfer });
    dragOverAt(target, transfer, 10);

    expect(screen.getByTestId('conversation-gap-before-c1')).toHaveStyle({ borderTop: '2px solid #1677ff' });
    expect(target.style.boxShadow).toBe('');
  });

  it('reorders through an explicit gap drop target', async () => {
    const preference = vi.fn().mockResolvedValue(undefined);
    hooks.useConversationPreference.mockReturnValue({ mutateAsync: preference, isPending: false });
    render(<QueryClientProvider client={new QueryClient()}><MemoryRouter><ContactsPage /></MemoryRouter></QueryClientProvider>);
    const source = screen.getByTestId('conversation-c2');
    const gap = screen.getByTestId('conversation-gap-before-c1');
    const transfer = dataTransfer();

    fireEvent.dragStart(source, { dataTransfer: transfer });
    fireEvent.dragOver(gap, { dataTransfer: transfer });
    fireEvent.drop(gap, { dataTransfer: transfer });

    await waitFor(() => expect(preference).toHaveBeenCalledWith({
      action: 'order',
      sourceType: 'CONTACT',
      sourceId: 'c2',
      targetType: 'CONTACT',
      targetId: 'c1',
      placement: 'BEFORE',
    }));
  });

  it('disables drag reordering while search is active', () => {
    hooks.useUnifiedConversations.mockImplementation((search?: string) => ({ data: { records: [
      { type: 'CONTACT', id: 'c1', displayName: '客户甲', channelTypes: ['email'], lastMessageAt: null, lastText: '', messageCount: 0, unreadCount: 0, pinned: false },
    ], total: 1 }, isLoading: false, search }));
    render(<QueryClientProvider client={new QueryClient()}><MemoryRouter><ContactsPage /></MemoryRouter></QueryClientProvider>);

    fireEvent.change(screen.getByPlaceholderText('搜索联系人、邮箱、号码'), { target: { value: '客户' } });

    expect(screen.getByTestId('conversation-c1')).toHaveAttribute('draggable', 'false');
  });

  it('merges contacts only when dropped onto the center overlap area', async () => {
    const merge = vi.fn().mockResolvedValue(undefined);
    hooks.useMergeContacts.mockReturnValue({ mutateAsync: merge, isPending: false });
    render(<QueryClientProvider client={new QueryClient()}><MemoryRouter><ContactsPage /></MemoryRouter></QueryClientProvider>);
    const source = screen.getByTestId('conversation-c1');
    const target = screen.getByTestId('conversation-c2');
    Object.defineProperty(target, 'getBoundingClientRect', {
      value: () => ({ top: 0, bottom: 100, left: 0, right: 200, width: 200, height: 100, x: 0, y: 0, toJSON: () => ({}) }),
    });
    const transfer = dataTransfer();

    fireEvent.dragStart(source, { dataTransfer: transfer });
    dragOverAt(target, transfer, 50);
    expect(target).toHaveAttribute('data-drop-mode', 'merge');
    fireEvent.drop(target, { dataTransfer: transfer, clientY: 50 });

    await waitFor(() => expect(merge).toHaveBeenCalledWith({ sourceContactId: 'c1', targetContactId: 'c2' }));
    expect(hooks.useConversationPreference().mutateAsync).not.toHaveBeenCalled();
  });

  it('treats the center of a group row as ordering rather than merging', async () => {
    const preference = vi.fn().mockResolvedValue(undefined);
    const merge = vi.fn().mockResolvedValue(undefined);
    hooks.useConversationPreference.mockReturnValue({ mutateAsync: preference, isPending: false });
    hooks.useMergeContacts.mockReturnValue({ mutateAsync: merge, isPending: false });
    hooks.useUnifiedConversations.mockReturnValue({ data: { records: [
      { type: 'CONTACT', id: 'c1', displayName: '客户甲', channelTypes: ['email'], lastMessageAt: null, lastText: '', messageCount: 0, unreadCount: 0, pinned: false },
      { type: 'WECOM_GROUP', id: 'g1', displayName: '项目群', channelTypes: ['wecom'], lastMessageAt: null, lastText: '', messageCount: 0, unreadCount: 0, pinned: false, providerConversationKey: 'room-1', participantCount: 3 },
    ], total: 2 }, isLoading: false });
    render(<QueryClientProvider client={new QueryClient()}><MemoryRouter><ContactsPage /></MemoryRouter></QueryClientProvider>);
    const source = screen.getByTestId('conversation-c1');
    const target = screen.getByTestId('conversation-g1');
    Object.defineProperty(target, 'getBoundingClientRect', {
      value: () => ({ top: 0, bottom: 100, left: 0, right: 200, width: 200, height: 100, x: 0, y: 0, toJSON: () => ({}) }),
    });
    const transfer = dataTransfer();

    fireEvent.dragStart(source, { dataTransfer: transfer });
    dragOverAt(target, transfer, 50);
    expect(target).toHaveAttribute('data-drop-mode', 'after');
    fireEvent.drop(target, { dataTransfer: transfer, clientY: 50 });

    await waitFor(() => expect(preference).toHaveBeenCalledWith({
      action: 'order',
      sourceType: 'CONTACT',
      sourceId: 'c1',
      targetType: 'WECOM_GROUP',
      targetId: 'g1',
      placement: 'AFTER',
    }));
    expect(merge).not.toHaveBeenCalled();
  });
});
