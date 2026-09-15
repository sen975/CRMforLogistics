import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import ChannelAddressBookList from './ChannelAddressBookList';

const item = {
  contactId: 'contact-1', identityId: 'identity-1', displayName: '张经理', remark: null,
  channelType: 'email' as const, address: 'a@example.com', channelDisplayName: '邮件',
  additionalChannelTypes: [], source: 'manual', lastContactAt: null, hasActivity: true,
  canDelete: false,
};

describe('ChannelAddressBookList matched tags', () => {
  it('renders matched tags next to the contact name', () => {
    render(<ChannelAddressBookList channel="email" items={[{ ...item, matchedTags: ['VIP客户'] }]}
      onOpen={() => {}} onDelete={() => {}} />);

    expect(screen.getByText('VIP客户')).toBeInTheDocument();
  });

  it('renders nothing extra without matched tags', () => {
    render(<ChannelAddressBookList channel="email" items={[item]}
      onOpen={() => {}} onDelete={() => {}} />);

    expect(screen.queryByText('VIP客户')).not.toBeInTheDocument();
  });
});
