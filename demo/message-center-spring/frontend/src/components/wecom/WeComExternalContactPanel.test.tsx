import '@testing-library/jest-dom/vitest';
import { ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import WeComExternalContactPanel from './WeComExternalContactPanel';

const api = vi.hoisted(() => ({
  fetchWeComBinding: vi.fn(),
  listWeComExternalContacts: vi.fn(),
  getWeComExternalContact: vi.fn(),
}));

vi.mock('../../api/endpoints', () => api);

describe('WeComExternalContactPanel', () => {
  it('requires a bound WeCom identity before loading customers', async () => {
    api.fetchWeComBinding.mockResolvedValue({ bound: false });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={queryClient}><ConfigProvider><WeComExternalContactPanel authCorpId="corp-1" /></ConfigProvider></QueryClientProvider>);
    expect(await screen.findByText('当前账号尚未绑定企业微信')).toBeInTheDocument();
    expect(api.listWeComExternalContacts).not.toHaveBeenCalled();
  });

  it('loads each bound customer detail and renders nicknames instead of raw ids', async () => {
    api.fetchWeComBinding.mockResolvedValue({ bound: true, authCorpId: 'corp-1', wecomUserId: 'employee-1' });
    api.listWeComExternalContacts.mockResolvedValue({
      external_userid: ['external-1', 'external-2'],
    });
    api.getWeComExternalContact.mockImplementation(async (_corp: string, externalId: string) => ({
      external_contact: { external_userid: externalId, name: externalId === 'external-1' ? '客户一' : '客户二' },
    }));

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={queryClient}><ConfigProvider><WeComExternalContactPanel authCorpId="corp-1" /></ConfigProvider></QueryClientProvider>);

    await waitFor(() => expect(api.listWeComExternalContacts).toHaveBeenCalledWith('corp-1'));
    await waitFor(() => expect(api.getWeComExternalContact).toHaveBeenCalledWith('corp-1', 'external-1'));
    await waitFor(() => expect(api.getWeComExternalContact).toHaveBeenCalledWith('corp-1', 'external-2'));
    expect(await screen.findByText('客户一')).toBeInTheDocument();
    expect(screen.getByText('客户二')).toBeInTheDocument();
    expect(screen.queryByText('external-1', { selector: '.ant-typography' })).not.toBeInTheDocument();
  });
});
