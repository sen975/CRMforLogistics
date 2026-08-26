import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { WeComGroupHeader } from './WeComGroupHeader';

describe('WeComGroupHeader', () => {
  it('shows the native action only when the backend supplies an allowed URL', () => {
    const { rerender } = render(<WeComGroupHeader thread={{ displayName: '群 A', avatarUrl: null, participants: [], openClientUrl: null }} />);
    expect(screen.queryByRole('link', { name: '在企业微信中打开' })).not.toBeInTheDocument();
    rerender(<WeComGroupHeader thread={{ displayName: '群 A', avatarUrl: null, participants: [], openClientUrl: 'wxwork://message?chatid=1' }} />);
    expect(screen.getByRole('link', { name: '在企业微信中打开' })).toHaveAttribute('href', 'wxwork://message?chatid=1');
  });
});
