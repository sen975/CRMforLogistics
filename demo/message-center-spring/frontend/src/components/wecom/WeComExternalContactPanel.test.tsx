import '@testing-library/jest-dom/vitest';
import { ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import WeComExternalContactPanel from './WeComExternalContactPanel';

const api = vi.hoisted(() => ({
  listWeComDirectoryMembers: vi.fn(),
  listWeComExternalContacts: vi.fn(),
  getWeComExternalContact: vi.fn(),
}));

vi.mock('../../api/endpoints', () => api);

describe('WeComExternalContactPanel', () => {
  it('shows the internal member selector before loading customers', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={queryClient}><ConfigProvider><WeComExternalContactPanel authCorpId="corp-1" /></ConfigProvider></QueryClientProvider>);
    expect(screen.getByLabelText('客户联系成员 ID')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '加载客户' })).toBeInTheDocument();
  });

  it('renders directory members and fetched external contacts as selectable entries', async () => {
    api.listWeComDirectoryMembers.mockResolvedValue({
      userlist: [{ userid: 'employee-1', name: '员工一' }],
    });
    api.listWeComExternalContacts.mockResolvedValue({
      external_userid: ['external-1', 'external-2'],
    });
    api.getWeComExternalContact.mockResolvedValue({ external_contact: { name: '客户一' } });

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={queryClient}><ConfigProvider><WeComExternalContactPanel authCorpId="corp-1" /></ConfigProvider></QueryClientProvider>);

    const user = userEvent.setup();
    await user.click(screen.getByRole('combobox', { name: '客户联系成员' }));
    await user.click(await screen.findByText('员工一 (employee-1)'));
    await waitFor(() => expect(api.listWeComExternalContacts).toHaveBeenCalledWith('corp-1', 'employee-1'));
    expect(await screen.findByText('external-1')).toBeInTheDocument();
    expect(screen.getByText('external-2')).toBeInTheDocument();
  });
});
