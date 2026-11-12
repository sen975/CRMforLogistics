import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { WeComGroupParticipants } from './WeComGroupParticipants';

describe('WeComGroupParticipants', () => {
  it('displays participant types as employee and customer labels', () => {
    render(<WeComGroupParticipants participants={[
      { partyId: '1', partyType: 'EMPLOYEE', providerPartyId: 'a', displayName: '张三', contactAccessible: false, contactId: null, isCurrentViewer: false },
      { partyId: '2', partyType: 'EXTERNAL_CONTACT', providerPartyId: 'b', displayName: '李四', contactAccessible: false, contactId: null, isCurrentViewer: false },
    ]} />);

    expect(screen.getByText('员工')).toBeInTheDocument();
    expect(screen.getByText('客户')).toBeInTheDocument();
    expect(screen.queryByText('EMPLOYEE')).not.toBeInTheDocument();
    expect(screen.queryByText('EXTERNAL_CONTACT')).not.toBeInTheDocument();
  });

  it('links only participants with backend access', () => {
    render(<WeComGroupParticipants participants={[
      { partyId: '1', partyType: 'EMPLOYEE', providerPartyId: 'a', displayName: '员工 A', contactAccessible: false, contactId: null, isCurrentViewer: false },
      { partyId: '2', partyType: 'EXTERNAL_CONTACT', providerPartyId: 'b', displayName: '客户 B', contactAccessible: true, contactId: 'c2', isCurrentViewer: false },
    ]} />);
    expect(screen.queryByRole('link', { name: /员工 A/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: /客户 B/ })).toHaveAttribute('href', '/conversations/contact/c2');
  });

  it('uses an explicit missing-profile label instead of the provider id', () => {
    render(<WeComGroupParticipants participants={[
      { partyId: '3', partyType: 'EMPLOYEE', providerPartyId: 'woi-long-provider-id', displayName: '', contactAccessible: false, contactId: null, isCurrentViewer: false },
    ]} />);
    expect(screen.getByText('未获取昵称')).toBeInTheDocument();
    expect(screen.queryByText('woi-long-provider-id')).not.toBeInTheDocument();
  });
});
