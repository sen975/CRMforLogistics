import '@testing-library/jest-dom/vitest';
import { ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import WeComCustomerGroupPanel from './WeComCustomerGroupPanel';

const api = vi.hoisted(() => ({ searchWeComCustomerGroups: vi.fn(), fetchWeComCustomerGroup: vi.fn() }));
vi.mock('../../api/endpoints', () => api);

describe('WeComCustomerGroupPanel', () => {
  it('shows the bounded customer group search action', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={queryClient}><ConfigProvider><WeComCustomerGroupPanel authCorpId="corp-1" /></ConfigProvider></QueryClientProvider>);
    expect(screen.getByRole('button', { name: '查询客户群' })).toBeInTheDocument();
  });

  it('renders returned customer groups as selectable entries', async () => {
    api.searchWeComCustomerGroups.mockResolvedValue({ group_chat_id_list: ['group-1', 'group-2'] });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={queryClient}><ConfigProvider><WeComCustomerGroupPanel authCorpId="corp-1" /></ConfigProvider></QueryClientProvider>);
    await screen.getByRole('button', { name: '查询客户群' }).click();
    expect(await screen.findByText('group-1')).toBeInTheDocument();
    expect(screen.getByText('group-2')).toBeInTheDocument();
  });
});
