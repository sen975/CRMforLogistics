import '@testing-library/jest-dom/vitest';
import { App as AntApp, ConfigProvider } from 'antd';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ChannelAddressBookPage from './ChannelAddressBookPage';

const hooks = vi.hoisted(() => ({
  useChannelAddressBook: vi.fn(),
  useCreateManualChannelContact: vi.fn(),
  useDeleteManualChannelContact: vi.fn(),
}));

vi.mock('../hooks/useContacts', () => hooks);

function Location() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname}{location.search}</output>;
}

function renderPage(initialEntry = '/address-book/chatapp') {
  return render(
    <ConfigProvider>
      <AntApp>
        <MemoryRouter initialEntries={[initialEntry]}>
          <Routes>
            <Route path="/address-book/:channel" element={<ChannelAddressBookPage />} />
          </Routes>
          <Location />
        </MemoryRouter>
      </AntApp>
    </ConfigProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  hooks.useChannelAddressBook.mockReturnValue({
    data: {
      items: [{
        contactId: 'contact-1', identityId: 'identity-1', displayName: '张经理', remark: '张经理',
        channelType: 'chatapp', address: '+8613812345678', channelDisplayName: 'WhatsApp',
        additionalChannelTypes: ['email', 'phone'], source: 'synced', lastContactAt: null,
        hasActivity: true, canDelete: false,
      }],
      page: 1, size: 20, hasMore: true,
    },
    isLoading: false,
  });
  hooks.useCreateManualChannelContact.mockReturnValue({ mutate: vi.fn(), isPending: false });
  hooks.useDeleteManualChannelContact.mockReturnValue({ mutate: vi.fn(), variables: undefined });
});

describe('ChannelAddressBookPage', () => {
  it('uses channel-scoped search and opens the selected identity timeline', async () => {
    const user = userEvent.setup();
    renderPage();

    expect(screen.getByRole('heading', { name: 'WhatsApp 通讯录' })).toBeInTheDocument();
    expect(screen.getByText('张经理')).toBeInTheDocument();
    expect(screen.getByText('【邮件】')).toBeInTheDocument();
    expect(screen.getByText('【电话】')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '删除联系人' })).not.toBeInTheDocument();

    await user.type(screen.getByPlaceholderText('搜索名称、备注、号码或邮箱'), '张');
    await user.keyboard('{Enter}');
    await waitFor(() => expect(hooks.useChannelAddressBook).toHaveBeenLastCalledWith('chatapp', '张', 1, 'contact'));

    await user.click(screen.getByText('张经理'));
    expect(screen.getByTestId('location')).toHaveTextContent('/conversations/contact/contact-1?channel=chatapp&identityId=identity-1');
  });

  it('switches the request page through the address-book pagination control', async () => {
    const user = userEvent.setup();
    renderPage('/address-book/email');

    await user.click(screen.getByRole('listitem', { name: '2' }));
    await waitFor(() => expect(hooks.useChannelAddressBook).toHaveBeenLastCalledWith('email', '', 2, 'contact'));
  });

  it('clears the keyword and page and searches tag names when switching to tag mode', async () => {
    const user = userEvent.setup();
    renderPage('/address-book/email');

    await user.type(screen.getByPlaceholderText('搜索名称、备注、号码或邮箱'), '张');
    await user.keyboard('{Enter}');
    await user.click(screen.getByRole('listitem', { name: '2' }));
    await waitFor(() => expect(hooks.useChannelAddressBook).toHaveBeenLastCalledWith('email', '张', 2, 'contact'));

    await user.click(screen.getByRole('button', { name: '搜索模式' }));
    await user.click(await screen.findByText('标签'));

    await waitFor(() => expect(hooks.useChannelAddressBook).toHaveBeenLastCalledWith('email', '', 1, 'tag'));
    expect(screen.getByPlaceholderText('搜索标签名')).toHaveValue('');
  });

  it('shows enterprise WeCom when it is an additional contact channel', () => {
    hooks.useChannelAddressBook.mockReturnValueOnce({
      data: {
        items: [{
          contactId: 'contact-wecom', identityId: 'identity-wecom', displayName: '企业客户', remark: null,
          channelType: 'chatapp', address: '+8613800000000', channelDisplayName: 'WhatsApp',
          additionalChannelTypes: ['wecom'], source: 'synced', lastContactAt: null,
          hasActivity: false, canDelete: false,
        }], page: 1, size: 20, hasMore: false,
      }, isLoading: false,
    });
    renderPage();

    expect(screen.getByText('【企业微信】')).toBeInTheDocument();
    expect(screen.queryByText('【】')).not.toBeInTheDocument();
  });

  it('saves a manual phone contact and opens its read-only channel timeline', async () => {
    const create = vi.fn();
    hooks.useCreateManualChannelContact.mockReturnValue({ mutate: create, isPending: false });
    renderPage('/address-book/phone');

    fireEvent.click(screen.getByRole('button', { name: '新增联系人' }));
    fireEvent.change(screen.getByLabelText('显示名称'), { target: { value: '李女士' } });
    fireEvent.change(screen.getByLabelText('号码'), { target: { value: '13812345678' } });
    fireEvent.click(screen.getByRole('button', { name: '保存' }));

    await waitFor(() => expect(create).toHaveBeenCalledWith(
      { displayName: '李女士', address: '13812345678' },
      expect.objectContaining({ onSuccess: expect.any(Function) }),
    ));
  });
});
