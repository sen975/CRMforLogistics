import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { WeComGroupParticipants } from './WeComGroupParticipants';

describe('WeCom sender identity projection', () => {
  it('renders robot and employee names without exposing provider ids as labels', () => {
    render(<WeComGroupParticipants participants={[
      { partyId: '1', partyType: 'ROBOT', providerPartyId: 'bot-long-id', displayName: '通知机器人', contactAccessible: false, contactId: null, isCurrentViewer: false },
      { partyId: '2', partyType: 'EMPLOYEE', providerPartyId: 'employee-long-id', displayName: '员工 A', contactAccessible: false, contactId: null, isCurrentViewer: false },
    ]} />);
    expect(screen.getByText('通知机器人')).toBeVisible();
    expect(screen.getByText('员工 A')).toBeVisible();
    expect(screen.queryByText('bot-long-id')).not.toBeInTheDocument();
  });
});
