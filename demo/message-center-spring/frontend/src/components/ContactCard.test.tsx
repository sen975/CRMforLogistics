import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import ContactCard from './ContactCard';
import type { ContactResponse } from '../api/types';

const contact: ContactResponse = {
  id: 'contact-1',
  displayName: '8613428277520',
  remark: '',
  channelTypes: ['phone'],
  unreadCount: 0,
  messageCount: 1,
  lastMessageAt: '2026-08-16T06:00:00Z',
  lastText: '通话记录',
  identities: [],
};

describe('ContactCard channel icons', () => {
  it('renders a stable initial avatar with channel markers', () => {
    const { rerender } = render(<ContactCard contact={contact} isActive={false} onClick={() => undefined} />);
    const avatar = screen.getByTestId('contact-avatar');
    expect(avatar).toHaveTextContent('8');
    expect(avatar.querySelector('[data-channel="phone"]')).toBeInTheDocument();
    const colorClass = avatar.className;
    rerender(<ContactCard contact={contact} isActive onClick={() => undefined} />);
    expect(screen.getByTestId('contact-avatar')).toHaveClass(colorClass.split(' ').find((name) => name.startsWith('contact-avatar-color-'))!);
    expect(screen.getByText(contact.displayName).closest('.conversation-contact')).toHaveClass('is-active');
  });

  it('uses the first visible character for a Chinese contact name', () => {
    render(<ContactCard contact={{ ...contact, displayName: '守望' }} isActive={false} onClick={() => undefined} />);

    expect(screen.getByTestId('contact-avatar')).toHaveTextContent('守');
  });

  it('leaves the preview blank when the conversation has no message', () => {
    render(<ContactCard contact={{ ...contact, lastText: '', messageCount: 0 }} isActive={false} onClick={() => undefined} />);

    expect(screen.queryByText('暂无消息')).not.toBeInTheDocument();
  });

  it('shows a labelled pin icon for a pinned contact', () => {
    render(<ContactCard contact={{ ...contact, pinned: true }} isActive={false} onClick={() => undefined} />);

    expect(screen.getByLabelText('已置顶')).toBeInTheDocument();
  });
});
