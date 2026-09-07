import { App as AntApp, ConfigProvider } from 'antd';
import { useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ContactResponse } from '../api/types';
import SendForm from './SendForm';

const api = vi.hoisted(() => ({
  sendEmail: vi.fn(),
  sendChatApp: vi.fn(),
  sendWeCom: vi.fn(),
  sendChatAppMedia: vi.fn(),
  fetchTemplates: vi.fn(),
  fetchChannelCapabilities: vi.fn(),
  createCallRecord: vi.fn(),
  bindPhoneContact: vi.fn(),
  fetchContacts: vi.fn(),
}));

vi.mock('../api/endpoints', () => api);

function contact(identities: ContactResponse['identities']): ContactResponse {
  return {
    id: 'contact-1',
    displayName: '当前联系人',
    remark: '',
    channelTypes: [...new Set(identities.map((identity) => identity.channelType))],
    lastMessageAt: null,
    lastText: '',
    messageCount: 0,
    unreadCount: 0,
    identities,
  };
}

function renderForm(
  value: ContactResponse,
  selectedChannelAccountId?: string,
  activeChannel?: string,
  onChannelChange?: (channel: string) => void,
  selectedIdentityId?: string,
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        <AntApp>
          <SendForm
            contact={value}
            selectedChannelAccountId={selectedChannelAccountId}
            activeChannel={activeChannel}
            onChannelChange={onChannelChange}
            selectedIdentityId={selectedIdentityId}
          />
        </AntApp>
      </ConfigProvider>
    </QueryClientProvider>,
  );
  return { ...view, queryClient };
}

function ControlledSendForm({
  contact: value,
  initialChannel,
  onChannelChange,
}: {
  contact: ContactResponse;
  initialChannel: string;
  onChannelChange: (channel: string) => void;
}) {
  const [activeChannel, setActiveChannel] = useState(initialChannel);
  return (
    <SendForm
      contact={value}
      activeChannel={activeChannel}
      onChannelChange={(channel) => {
        onChannelChange(channel);
        setActiveChannel(channel);
      }}
    />
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchTemplates.mockResolvedValue([]);
  api.fetchChannelCapabilities.mockResolvedValue([{
    channelType: 'chatapp',
    channelAccountId: 'phone',
    displayName: 'ChatApp',
    authStatus: 'active',
  }]);
  api.fetchContacts.mockResolvedValue({ records: [] });
  api.sendChatApp.mockResolvedValue(undefined);
});

describe('SendForm ChatApp recipient binding', () => {
  it('uses the address-book-selected identity instead of the first email identity', async () => {
    renderForm(contact([
      { id: 'email-first', channelType: 'email', identityScope: 'account-a', identityValue: 'first@example.com', displayName: '第一邮箱' },
      { id: 'email-selected', channelType: 'email', identityScope: 'account-a', identityValue: 'selected@example.com', displayName: '目标邮箱' },
    ]), undefined, 'email', undefined, 'email-selected');

    await waitFor(() => {
      expect(screen.getByText('目标邮箱 (selected@example.com)')).toBeInTheDocument();
    });
  });

  it('uses the address-book-selected ChatApp identity and its account scope', async () => {
    api.fetchChannelCapabilities.mockResolvedValue([
      { channelType: 'chatapp', channelAccountId: 'account-a', displayName: '账号 A', authStatus: 'active' },
      { channelType: 'chatapp', channelAccountId: 'account-b', displayName: '账号 B', authStatus: 'active' },
    ]);
    renderForm(contact([
      { id: 'chatapp-a', channelType: 'chatapp', identityScope: 'account-a', identityValue: '111', displayName: '账号 A 联系人' },
      { id: 'chatapp-b', channelType: 'chatapp', identityScope: 'account-b', identityValue: '222', displayName: '账号 B 联系人' },
    ]), undefined, 'chatapp', undefined, 'chatapp-b');

    expect(await screen.findByLabelText('收件人')).toHaveValue('账号 B 联系人 (222)');
  });

  it('reports the selected send channel through the controlled channel owner', async () => {
    const user = userEvent.setup();
    const onChannelChange = vi.fn();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <ConfigProvider>
          <AntApp>
            <ControlledSendForm
              contact={contact([
                { id: 'wecom-1', channelType: 'wecom', identityScope: 'external', identityValue: 'external-1', displayName: '客户一' },
                { id: 'email-1', channelType: 'email', identityScope: 'email', identityValue: 'customer@example.com', displayName: '客户一' },
              ])}
              initialChannel="email"
              onChannelChange={onChannelChange}
            />
          </AntApp>
        </ConfigProvider>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('tab', { name: '邮件' })).toHaveAttribute('aria-selected', 'true');
    await user.click(screen.getByRole('tab', { name: '企业微信' }));
    expect(onChannelChange).toHaveBeenCalledWith('wecom');

    await user.click(screen.getByRole('tab', { name: '邮件' }));
    expect(onChannelChange).toHaveBeenLastCalledWith('email');
  });

  it('shows the template display name while sending its official template name', async () => {
    const user = userEvent.setup();
    api.fetchTemplates.mockResolvedValue([{
      templateCode: 'delivery-notice',
      templateName: 'delivery_notice',
      displayName: '发货提醒（delivery_notice）',
      languageCode: 'zh_CN',
      body: '您的货物已发出',
      placeholders: [],
    }]);
    renderForm(contact([{
      id: 'chatapp-1',
      channelType: 'chatapp',
      identityScope: 'phone',
      identityValue: '16465894168',
      displayName: '主账号',
    }]));

    await user.click(await screen.findByRole('tab', { name: 'ChatApp' }));
    await user.click(screen.getByRole('tab', { name: '模板' }));
    const templateSelect = screen.getByRole('combobox', { name: '模板' });
    fireEvent.mouseDown(templateSelect);
    expect(await screen.findByText('发货提醒（delivery_notice） (zh_CN)')).toBeInTheDocument();

    await user.click(screen.getByText('发货提醒（delivery_notice） (zh_CN)', { exact: true }));
    await user.click(screen.getByRole('button', { name: /发送模板$/ }));

    await waitFor(() => expect(api.sendChatApp).toHaveBeenCalledTimes(1));
    expect(api.sendChatApp).toHaveBeenCalledWith(expect.objectContaining({
      mode: 'template',
      templateName: 'delivery_notice',
    }));
    expect(api.sendChatApp.mock.calls[0][0].templateName).not.toBe('发货提醒（delivery_notice）');
  });

  it('previews the selected template body and replaces entered parameters live', async () => {
    const user = userEvent.setup();
    api.fetchTemplates.mockResolvedValue([{
      templateCode: 'delivery-notice',
      templateName: 'delivery_notice',
      displayName: '发货提醒',
      languageCode: 'zh_CN',
      body: '您好，{{name}}，预计 {{date}} 发货。',
      placeholders: ['name', 'date'],
    }]);
    renderForm(contact([{
      id: 'chatapp-1',
      channelType: 'chatapp',
      identityScope: 'phone',
      identityValue: '16465894168',
      displayName: '主账号',
    }]));

    await user.click(await screen.findByRole('tab', { name: 'ChatApp' }));
    await user.click(screen.getByRole('tab', { name: '模板' }));
    const templateSelect = screen.getByRole('combobox', { name: '模板' });
    fireEvent.mouseDown(templateSelect);
    await user.click(await screen.findByText('发货提醒 (zh_CN)', { exact: true }));

    expect(screen.getByLabelText('模板预览')).toHaveTextContent('发货提醒');
    expect(screen.getByLabelText('模板预览')).toHaveTextContent('您好，{{name}}，预计 {{date}} 发货。');

    await user.type(screen.getByLabelText('name'), '张三');
    await user.type(screen.getByLabelText('date'), '明天');
    expect(screen.getByLabelText('模板预览')).toHaveTextContent('您好，张三，预计 明天 发货。');
  });

  it('renders one ChatApp identity as a read-only recipient', async () => {
    renderForm(contact([{
      id: 'chatapp-1',
      channelType: 'chatapp',
      identityScope: 'phone',
      identityValue: '16465894168',
      displayName: '主账号',
    }]));

    const recipient = await screen.findByLabelText('收件人');
    expect(recipient).toHaveValue('主账号 (16465894168)');
    expect(recipient).toHaveAttribute('readonly');
    expect(screen.queryByRole('combobox', { name: '收件人' })).not.toBeInTheDocument();
  });

  it('uses only identities scoped to the active ChatApp account', async () => {
    api.fetchChannelCapabilities.mockResolvedValue([{
      channelType: 'chatapp',
      channelAccountId: 'account-a',
      displayName: 'CAMS 一号账号',
      authStatus: 'active',
    }]);
    renderForm(contact([
      {
        id: 'chatapp-account-b',
        channelType: 'chatapp',
        identityScope: 'account-b',
        identityValue: '16465894169',
        displayName: '二号账号收件人',
      },
      {
        id: 'chatapp-account-a',
        channelType: 'chatapp',
        identityScope: 'account-a',
        identityValue: '16465894168',
        displayName: '一号账号收件人',
      },
    ]));

    const recipient = await screen.findByLabelText('收件人');
    expect(recipient).toHaveValue('一号账号收件人 (16465894168)');
    expect(screen.queryByRole('combobox', { name: '收件人' })).not.toBeInTheDocument();
  });

  it('lets ThreadPage select only active ChatApp accounts owned by the current contact', async () => {
    const user = userEvent.setup();
    api.fetchChannelCapabilities.mockResolvedValue([
      { channelType: 'chatapp', channelAccountId: 'account-a', displayName: 'CAMS 一号账号', authStatus: 'active' },
      { channelType: 'chatapp', channelAccountId: 'account-b', displayName: 'CAMS 二号账号', authStatus: 'active' },
      { channelType: 'chatapp', channelAccountId: 'account-c', displayName: '停用账号', authStatus: 'inactive' },
      { channelType: 'chatapp', channelAccountId: 'account-d', displayName: '无联系人账号', authStatus: 'active' },
    ]);
    renderForm(contact([
      { id: 'chatapp-a', channelType: 'chatapp', identityScope: 'account-a', identityValue: '16465894168', displayName: '一号收件人' },
      { id: 'chatapp-b', channelType: 'chatapp', identityScope: 'account-b', identityValue: '16465894169', displayName: '二号收件人' },
      { id: 'chatapp-c', channelType: 'chatapp', identityScope: 'account-c', identityValue: '16465894170', displayName: '停用收件人' },
    ]));

    const accountSelect = await screen.findByRole('combobox', { name: 'ChatApp 账号' });
    fireEvent.mouseDown(accountSelect);
    expect(await screen.findByRole('option', { name: 'CAMS 一号账号' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'CAMS 二号账号' })).toBeInTheDocument();
    expect(screen.getAllByRole('option')).toHaveLength(2);
    expect(await screen.findByLabelText('收件人')).toHaveValue('一号收件人 (16465894168)');

    await user.click(screen.getByText('CAMS 二号账号', { selector: '.ant-select-item-option-content' }));
    expect(await screen.findByLabelText('收件人')).toHaveValue('二号收件人 (16465894169)');
  });

  it('does not render an internal ChatApp account selector when SendPage fixes the account', async () => {
    api.fetchChannelCapabilities.mockResolvedValue([
      { channelType: 'chatapp', channelAccountId: 'account-a', displayName: 'CAMS 一号账号', authStatus: 'active' },
      { channelType: 'chatapp', channelAccountId: 'account-b', displayName: 'CAMS 二号账号', authStatus: 'active' },
    ]);
    renderForm(contact([
      { id: 'chatapp-a', channelType: 'chatapp', identityScope: 'account-a', identityValue: '16465894168', displayName: '一号收件人' },
      { id: 'chatapp-b', channelType: 'chatapp', identityScope: 'account-b', identityValue: '16465894169', displayName: '二号收件人' },
    ]), 'account-a');

    expect(await screen.findByLabelText('收件人')).toHaveValue('一号收件人 (16465894168)');
    expect(screen.queryByRole('combobox', { name: 'ChatApp 账号' })).not.toBeInTheDocument();
  });

  it('keeps the ThreadPage ChatApp account and identity stable when capabilities reorder', async () => {
    let capabilities = [
      { channelType: 'chatapp', channelAccountId: 'account-a', displayName: 'CAMS 一号账号', authStatus: 'active' },
      { channelType: 'chatapp', channelAccountId: 'account-b', displayName: 'CAMS 二号账号', authStatus: 'active' },
    ];
    api.fetchChannelCapabilities.mockImplementation(() => Promise.resolve(capabilities));
    const { queryClient } = renderForm(contact([
      { id: 'chatapp-a-first', channelType: 'chatapp', identityScope: 'account-a', identityValue: '16465894168', displayName: '一号主收件人' },
      { id: 'chatapp-a-second', channelType: 'chatapp', identityScope: 'account-a', identityValue: '16465894169', displayName: '一号备用收件人' },
      { id: 'chatapp-b', channelType: 'chatapp', identityScope: 'account-b', identityValue: '16465894170', displayName: '二号收件人' },
    ]));
    const user = userEvent.setup();

    const recipient = await screen.findByRole('combobox', { name: '收件人' });
    fireEvent.mouseDown(recipient);
    await user.click(await screen.findByText('一号备用收件人 (16465894169)'));
    capabilities = [...capabilities].reverse();
    await queryClient.refetchQueries({ queryKey: ['channelCapabilities'] });

    await waitFor(() => expect(screen.getByText('CAMS 一号账号', {
      selector: '.ant-select-selection-item',
    })).toBeInTheDocument());
    expect(screen.getByText('一号备用收件人 (16465894169)', {
      selector: '.ant-select-selection-item',
    })).toBeInTheDocument();
  });

  it('clears the ThreadPage recipient choice when its selected account becomes inactive', async () => {
    let capabilities = [
      { channelType: 'chatapp', channelAccountId: 'account-a', displayName: 'CAMS 一号账号', authStatus: 'active' },
      { channelType: 'chatapp', channelAccountId: 'account-b', displayName: 'CAMS 二号账号', authStatus: 'active' },
    ];
    api.fetchChannelCapabilities.mockImplementation(() => Promise.resolve(capabilities));
    const { queryClient } = renderForm(contact([
      { id: 'chatapp-a-first', channelType: 'chatapp', identityScope: 'account-a', identityValue: '16465894168', displayName: '一号主收件人' },
      { id: 'chatapp-a-second', channelType: 'chatapp', identityScope: 'account-a', identityValue: '16465894169', displayName: '一号备用收件人' },
      { id: 'chatapp-b-first', channelType: 'chatapp', identityScope: 'account-b', identityValue: '16465894170', displayName: '二号主收件人' },
      { id: 'chatapp-b-second', channelType: 'chatapp', identityScope: 'account-b', identityValue: '16465894171', displayName: '二号备用收件人' },
    ]));
    const user = userEvent.setup();

    const accountSelect = await screen.findByRole('combobox', { name: 'ChatApp 账号' });
    fireEvent.mouseDown(accountSelect);
    await user.click(await screen.findByText('CAMS 二号账号', { selector: '.ant-select-item-option-content' }));
    const recipient = screen.getByRole('combobox', { name: '收件人' });
    fireEvent.mouseDown(recipient);
    await user.click(await screen.findByText('二号备用收件人 (16465894171)'));

    capabilities = [capabilities[0], { ...capabilities[1], authStatus: 'inactive' }];
    await queryClient.refetchQueries({ queryKey: ['channelCapabilities'] });

    await waitFor(() => expect(screen.getByRole('combobox', { name: '收件人' })).toHaveValue(''));
    expect(screen.queryByRole('combobox', { name: 'ChatApp 账号' })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: /发送$/ })).toBeDisabled());
  });

  it('requires recipient confirmation when an inactive ThreadPage account is replaced by one identity', async () => {
    let capabilities = [
      { channelType: 'chatapp', channelAccountId: 'account-a', displayName: 'CAMS 一号账号', authStatus: 'active' },
      { channelType: 'chatapp', channelAccountId: 'account-b', displayName: 'CAMS 二号账号', authStatus: 'active' },
    ];
    api.fetchChannelCapabilities.mockImplementation(() => Promise.resolve(capabilities));
    const { queryClient } = renderForm(contact([
      { id: 'chatapp-a', channelType: 'chatapp', identityScope: 'account-a', identityValue: '16465894168', displayName: '一号收件人' },
      { id: 'chatapp-b-first', channelType: 'chatapp', identityScope: 'account-b', identityValue: '16465894170', displayName: '二号主收件人' },
      { id: 'chatapp-b-second', channelType: 'chatapp', identityScope: 'account-b', identityValue: '16465894171', displayName: '二号备用收件人' },
    ]));
    const user = userEvent.setup();

    const accountSelect = await screen.findByRole('combobox', { name: 'ChatApp 账号' });
    fireEvent.mouseDown(accountSelect);
    await user.click(await screen.findByText('CAMS 二号账号', { selector: '.ant-select-item-option-content' }));
    const recipient = screen.getByRole('combobox', { name: '收件人' });
    fireEvent.mouseDown(recipient);
    await user.click(await screen.findByText('二号备用收件人 (16465894171)'));

    capabilities = [capabilities[0], { ...capabilities[1], authStatus: 'inactive' }];
    await queryClient.refetchQueries({ queryKey: ['channelCapabilities'] });

    await waitFor(() => expect(screen.getByRole('combobox', { name: '收件人' })).toHaveValue(''));
    expect(screen.queryByRole('combobox', { name: 'ChatApp 账号' })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: /发送$/ })).toBeDisabled());
    expect(screen.getByRole('combobox', { name: '收件人' })).toBeEnabled();
  });

  it('submits only another ChatApp identity owned by the current contact', async () => {
    const user = userEvent.setup();
    renderForm(contact([
      {
        id: 'chatapp-1',
        channelType: 'chatapp',
        identityScope: 'phone',
        identityValue: '16465894168',
        displayName: '主账号',
      },
      {
        id: 'chatapp-2',
        channelType: 'chatapp',
        identityScope: 'phone',
        identityValue: '16465894169',
        displayName: '备用账号',
      },
      {
        id: 'email-1',
        channelType: 'email',
        identityScope: 'email',
        identityValue: 'other@example.com',
        displayName: '邮箱',
      },
    ]));

    fireEvent.mouseDown(await screen.findByRole('combobox', { name: '收件人' }));
    await user.click(await screen.findByText('备用账号 (16465894169)'));
    await user.type(screen.getByLabelText('消息'), 'hello');
    await user.click(screen.getByRole('button', { name: /发送/ }));

    await waitFor(() => expect(api.sendChatApp).toHaveBeenCalledTimes(1));
    expect(api.sendChatApp).toHaveBeenCalledWith(expect.objectContaining({
      contactId: 'contact-1',
      recipientIdentityId: 'chatapp-2',
      mode: 'text',
      text: 'hello',
    }));
    expect(api.sendChatApp.mock.calls[0][0]).not.toHaveProperty('to');
  });

  it('keeps the selected ChatApp identity across every message mode', async () => {
    const user = userEvent.setup();
    renderForm(contact([
      {
        id: 'chatapp-1',
        channelType: 'chatapp',
        identityScope: 'phone',
        identityValue: '16465894168',
        displayName: '主账号',
      },
      {
        id: 'chatapp-2',
        channelType: 'chatapp',
        identityScope: 'phone',
        identityValue: '16465894169',
        displayName: '备用账号',
      },
    ]));

    fireEvent.mouseDown(await screen.findByRole('combobox', { name: '收件人' }));
    await user.click(await screen.findByText('备用账号 (16465894169)'));

    for (const mode of ['模板', '图片', '视频', '文件']) {
      await user.click(screen.getByRole('tab', { name: mode }));
      const panel = screen.getByRole('tabpanel', { name: mode });
      expect(within(panel).getByText('备用账号 (16465894169)')).toBeVisible();
    }
  });

  it('disables ChatApp sending when the current contact has no ChatApp identity', async () => {
    const user = userEvent.setup();
    renderForm(contact([]));

    await user.click(await screen.findByRole('tab', { name: 'ChatApp' }));
    expect(screen.getByLabelText('收件人')).toHaveValue('无可用 ChatApp 账号');
    expect(screen.getByRole('button', { name: /发送/ })).toBeDisabled();
  });

  it('hides ChatApp sending when the configured channel is inactive', async () => {
    api.fetchChannelCapabilities.mockResolvedValue([{
      channelType: 'chatapp',
      displayName: 'ChatApp',
      authStatus: 'inactive',
    }]);
    renderForm(contact([{
      id: 'chatapp-1',
      channelType: 'chatapp',
      identityScope: 'phone',
      identityValue: '16465894168',
      displayName: '主账号',
    }]));

    await screen.findByRole('tab', { name: '电话记录' });
    expect(screen.queryByRole('tab', { name: 'ChatApp' })).not.toBeInTheDocument();
  });

  it('keeps free-form phone entry in the call-record tab', async () => {
    const user = userEvent.setup();
    renderForm(contact([{
      id: 'chatapp-1',
      channelType: 'chatapp',
      identityScope: 'phone',
      identityValue: '16465894168',
      displayName: '主账号',
    }]));

    await user.click(await screen.findByRole('tab', { name: '电话记录' }));
    expect(await screen.findByPlaceholderText('例如: +8613800000000')).toBeEnabled();
  });
});
