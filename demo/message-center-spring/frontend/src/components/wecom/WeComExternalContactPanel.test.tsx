import '@testing-library/jest-dom/vitest';
import { ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import WeComExternalContactPanel from './WeComExternalContactPanel';

const api = vi.hoisted(() => ({
  fetchWeComBinding: vi.fn(),
  listWeComExternalContacts: vi.fn(),
  getWeComExternalContact: vi.fn(),
  listWeComExternalContactLinks: vi.fn(),
}));

vi.mock('../../api/endpoints', () => api);

function Location() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname}{location.search}</output>;
}

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider>
        <MemoryRouter initialEntries={['/settings/wecom']}>
          <Routes>
            <Route path="/settings/wecom" element={<WeComExternalContactPanel authCorpId="corp-1" />} />
            <Route path="/conversations/contact/:contactId" element={<div>联系人会话</div>} />
          </Routes>
          <Location />
        </MemoryRouter>
      </ConfigProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  api.listWeComExternalContactLinks.mockResolvedValue([]);
});

describe('WeComExternalContactPanel', () => {
  it('requires a bound WeCom identity before loading customers', async () => {
    api.fetchWeComBinding.mockResolvedValue({ bound: false });
    renderPanel();
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

    renderPanel();

    await waitFor(() => expect(api.listWeComExternalContacts).toHaveBeenCalledWith('corp-1'));
    await waitFor(() => expect(api.getWeComExternalContact).toHaveBeenCalledWith('corp-1', 'external-1'));
    await waitFor(() => expect(api.getWeComExternalContact).toHaveBeenCalledWith('corp-1', 'external-2'));
    await waitFor(() => expect(api.listWeComExternalContactLinks).toHaveBeenCalledWith('corp-1', ['external-1', 'external-2']));
    expect(await screen.findByText('客户一')).toBeInTheDocument();
    expect(screen.getByText('客户二')).toBeInTheDocument();
    expect(screen.queryByText('external-1', { selector: '.ant-typography' })).not.toBeInTheDocument();
  });

  it('opens the resolved CRM contact conversation for an accessible customer', async () => {
    const user = userEvent.setup();
    api.fetchWeComBinding.mockResolvedValue({ bound: true, authCorpId: 'corp-1', wecomUserId: 'employee-1' });
    api.listWeComExternalContacts.mockResolvedValue({ external_userid: ['external-1', 'external-2'] });
    api.getWeComExternalContact.mockImplementation(async (_corp: string, externalId: string) => ({
      external_contact: { external_userid: externalId, name: externalId === 'external-1' ? '客户一' : '客户二' },
    }));
    api.listWeComExternalContactLinks.mockResolvedValue([
      { externalUserId: 'external-1', contactId: 'contact-1', identityId: 'identity-1', accessible: true },
      { externalUserId: 'external-2', contactId: null, identityId: null, accessible: false },
    ]);

    renderPanel();

    const openable = await screen.findByRole('button', { name: '客户一' });
    await user.click(openable);

    expect(screen.getByTestId('location')).toHaveTextContent('/conversations/contact/contact-1?channel=wecom&identityId=identity-1');
  });

  it('keeps a customer without a CRM contact or without access as plain text', async () => {
    api.fetchWeComBinding.mockResolvedValue({ bound: true, authCorpId: 'corp-1', wecomUserId: 'employee-1' });
    api.listWeComExternalContacts.mockResolvedValue({ external_userid: ['external-2'] });
    api.getWeComExternalContact.mockResolvedValue({
      external_contact: { external_userid: 'external-2', name: '客户二' },
    });
    api.listWeComExternalContactLinks.mockResolvedValue([
      { externalUserId: 'external-2', contactId: 'contact-2', identityId: 'identity-2', accessible: false },
    ]);

    renderPanel();

    expect(await screen.findByText('客户二')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '客户二' })).not.toBeInTheDocument();
  });
});
