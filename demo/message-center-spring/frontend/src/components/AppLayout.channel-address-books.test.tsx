import '@testing-library/jest-dom/vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AppLayout from './AppLayout';

const authState = vi.hoisted(() => ({ username: '普通用户', profile: null, isAdmin: false, canBroadcast: false, logout: vi.fn() }));

vi.mock('../hooks/useAuth', () => ({ useAuth: () => authState }));
vi.mock('../pages/ContactsPage', () => ({ default: () => <div>联系人列表</div> }));
vi.mock('./ContactDetailPanel', () => ({ default: () => <div>联系人详情</div> }));
vi.mock('./CallRecordDetail', () => ({ default: () => <div>电话详情</div> }));
vi.mock('./WeComGroupDetailPanel', () => ({ default: () => <div>群详情</div> }));
vi.mock('./AccountPanel', () => ({ AccountPanel: () => <div>账号</div> }));
vi.mock('./AccountAvatar', () => ({ AccountAvatar: () => <div>头像</div> }));

function Location() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname}</output>;
}

function renderLayout() {
  render(<MemoryRouter><AppLayout /><Location /></MemoryRouter>);
}

beforeEach(() => vi.clearAllMocks());

describe('AppLayout channel address-book navigation', () => {
  it.each([
    ['WhatsApp', '/address-book/chatapp'],
    ['邮件', '/address-book/email'],
    ['电话', '/address-book/phone'],
  ])('opens %s address book from its channel menu', async (channel, expectedPath) => {
    renderLayout();
    fireEvent.click(screen.getByRole('button', { name: channel }));
    fireEvent.click(await screen.findByRole('menuitem', { name: '通讯录' }));
    expect(screen.getByTestId('location')).toHaveTextContent(expectedPath);
  });
});
