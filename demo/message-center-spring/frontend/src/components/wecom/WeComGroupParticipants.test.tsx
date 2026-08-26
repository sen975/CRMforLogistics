import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { WeComGroupParticipants } from './WeComGroupParticipants';

describe('WeComGroupParticipants', () => {
  it('links only participants with backend access', () => {
    render(<WeComGroupParticipants participants={[
      { partyId: '1', partyType: 'EMPLOYEE', providerPartyId: 'a', displayName: '员工 A', contactAccessible: false, contactId: null, isCurrentViewer: false },
      { partyId: '2', partyType: 'EXTERNAL_CONTACT', providerPartyId: 'b', displayName: '客户 B', contactAccessible: true, contactId: 'c2', isCurrentViewer: false },
    ]} />);
    expect(screen.queryByRole('link', { name: /员工 A/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: /客户 B/ })).toHaveAttribute('href', '/conversations/contact/c2');
  });
});
