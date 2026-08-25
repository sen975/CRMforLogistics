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
  it('uses the phone handset icon for phone contacts', () => {
    render(<ContactCard contact={contact} isActive={false} onClick={() => undefined} />);

    expect(screen.getByRole('img', { name: 'phone' })).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: 'message' })).not.toBeInTheDocument();
  });
});
