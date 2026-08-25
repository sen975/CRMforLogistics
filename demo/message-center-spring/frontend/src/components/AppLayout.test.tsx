import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import AppLayout from './AppLayout';

vi.mock('../hooks/useAuth', () => ({
  useAuth: () => ({ username: 'admin', isAdmin: true, canBroadcast: false, logout: vi.fn() }),
}));
vi.mock('../pages/ContactsPage', () => ({ default: () => <div>联系人列表</div> }));
vi.mock('./ContactDetailPanel', () => ({ default: () => <div>联系人详情</div> }));
vi.mock('./CallRecordDetail', () => ({ default: () => <div>通话详情</div> }));
vi.mock('./AccountPanel', () => ({ AccountPanel: () => <div>账号</div> }));

it('exposes the WeCom management entry for administrators', () => {
  render(<MemoryRouter><AppLayout /></MemoryRouter>);
  expect(screen.getByRole('button', { name: /企业微信/ })).toBeInTheDocument();
});
