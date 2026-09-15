import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import ContactCard from './ContactCard';

const base = {
  id: 'contact-1',
  displayName: '张经理',
  channelTypes: ['email'],
  lastMessageAt: null,
  lastText: '',
  messageCount: 0,
  unreadCount: 0,
};

describe('ContactCard matched tags', () => {
  it('renders matched tags when present', () => {
    render(<ContactCard contact={{ ...base, matchedTags: ['VIP客户', '老客户'] }}
      isActive={false} onClick={() => {}} />);

    expect(screen.getByText('VIP客户')).toBeInTheDocument();
    expect(screen.getByText('老客户')).toBeInTheDocument();
  });

  it('renders no tag chips without matched tags', () => {
    render(<ContactCard contact={{ ...base, matchedTags: [] }} isActive={false} onClick={() => {}} />);

    expect(screen.queryByText('VIP客户')).not.toBeInTheDocument();
  });
});
