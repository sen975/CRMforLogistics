import '@testing-library/jest-dom/vitest';
import { ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import WeComContactEventPanel from './WeComContactEventPanel';

const api = vi.hoisted(() => ({
  fetchWeComBinding: vi.fn(),
  listWeComContactEvents: vi.fn(),
}));

vi.mock('../../api/endpoints', () => api);

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        <WeComContactEventPanel authCorpId="corp-1" />
      </ConfigProvider>
    </QueryClientProvider>,
  );
}

const bound = { bound: true, authCorpId: 'corp-1', wecomUserId: 'employee-1' };

beforeEach(() => {
  vi.clearAllMocks();
});

describe('WeComContactEventPanel', () => {
  it('requires a bound WeCom identity before loading events', async () => {
    api.fetchWeComBinding.mockResolvedValue({ bound: false });
    renderPanel();

    expect(await screen.findByText('当前账号尚未绑定企业微信')).toBeInTheDocument();
    expect(api.listWeComContactEvents).not.toHaveBeenCalled();
  });

  it('warns when the bound identity belongs to another corp', async () => {
    api.fetchWeComBinding.mockResolvedValue({ ...bound, authCorpId: 'corp-other' });
    renderPanel();

    expect(await screen.findByText('当前账号绑定的企业微信企业与当前选择不一致')).toBeInTheDocument();
    expect(api.listWeComContactEvents).not.toHaveBeenCalled();
  });

  it('renders the event stream with Chinese labels and the raw id instead of a made-up name', async () => {
    api.fetchWeComBinding.mockResolvedValue(bound);
    api.listWeComContactEvents.mockResolvedValue([
      {
        id: 'event-1',
        changeType: 'add_external_contact',
        externalUserId: 'wmZZZZZZZZ',
        wecomUserId: 'woYYYYYYYY',
        state: 'baidu-channel',
        failReason: null,
        providerSource: null,
        providerCreatedAt: '2026-09-20T03:00:00Z',
      },
      {
        id: 'event-2',
        changeType: 'del_external_contact',
        externalUserId: 'wmZZZZZZZZ',
        wecomUserId: 'woYYYYYYYY',
        state: null,
        failReason: null,
        providerSource: 'DELETE_BY_TRANSFER',
        providerCreatedAt: '2026-09-21T03:00:00Z',
      },
    ]);
    renderPanel();

    expect(await screen.findByText('新增客户')).toBeInTheDocument();
    expect(screen.getByText('删除客户')).toBeInTheDocument();
    expect(screen.getAllByText('wmZZZZZZZZ')).toHaveLength(2);
    // 名称补齐是另一个接口的事；这一版不许伪造昵称
    expect(screen.queryByText('未获取昵称')).not.toBeInTheDocument();
    expect(screen.getByText(/渠道 baidu-channel/)).toBeInTheDocument();
    expect(screen.getByText(/在职继承自动转接/)).toBeInTheDocument();
  });

  it('labels an unknown change type with its raw value instead of folding it into 其他', async () => {
    api.fetchWeComBinding.mockResolvedValue(bound);
    api.listWeComContactEvents.mockResolvedValue([
      {
        id: 'event-3',
        changeType: 'some_future_change_type',
        externalUserId: 'wmZZZZZZZZ',
        wecomUserId: null,
        state: null,
        failReason: null,
        providerSource: null,
        providerCreatedAt: '2026-09-21T03:00:00Z',
      },
    ]);
    renderPanel();

    expect(await screen.findByText('some_future_change_type')).toBeInTheDocument();
  });

  it('falls back to 未获取昵称 when the event carries no external user id', async () => {
    api.fetchWeComBinding.mockResolvedValue(bound);
    api.listWeComContactEvents.mockResolvedValue([
      {
        id: 'event-4',
        changeType: 'transfer_fail',
        externalUserId: null,
        wecomUserId: 'woYYYYYYYY',
        state: null,
        failReason: 'customer_refused',
        providerSource: null,
        providerCreatedAt: '2026-09-21T03:00:00Z',
      },
    ]);
    renderPanel();

    expect(await screen.findByText('未获取昵称')).toBeInTheDocument();
    expect(screen.getByText('接替失败')).toBeInTheDocument();
    expect(screen.getByText(/客户拒绝/)).toBeInTheDocument();
  });

  it('explains that the stream only covers time since ingestion', async () => {
    api.fetchWeComBinding.mockResolvedValue(bound);
    api.listWeComContactEvents.mockResolvedValue([]);
    renderPanel();

    expect(await screen.findByText('自接入日起无客户关系变化')).toBeInTheDocument();
    expect(screen.getByText(/企业微信不提供历史事件查询/)).toBeInTheDocument();
  });

  it('surfaces a load failure without pretending the stream is empty', async () => {
    api.fetchWeComBinding.mockResolvedValue(bound);
    api.listWeComContactEvents.mockRejectedValue(new Error('boom'));
    renderPanel();

    expect(await screen.findByText('客户动态加载失败')).toBeInTheDocument();
    expect(screen.queryByText('自接入日起无客户关系变化')).not.toBeInTheDocument();
  });

  it('loads more events with an explicit button instead of an infinite scroll', async () => {
    const user = userEvent.setup();
    api.fetchWeComBinding.mockResolvedValue(bound);
    api.listWeComContactEvents.mockResolvedValue(Array.from({ length: 50 }, (_, index) => ({
      id: `event-${index}`,
      changeType: 'add_external_contact',
      externalUserId: `wm${index}`,
      wecomUserId: 'woYYYYYYYY',
      state: null,
      failReason: null,
      providerSource: null,
      providerCreatedAt: '2026-09-21T03:00:00Z',
    })));
    renderPanel();

    await waitFor(() => expect(api.listWeComContactEvents).toHaveBeenCalledWith('corp-1', { limit: 50 }));
    await user.click(await screen.findByRole('button', { name: '加载更多' }));

    await waitFor(() => expect(api.listWeComContactEvents).toHaveBeenCalledWith('corp-1', { limit: 100 }));
  });
});
