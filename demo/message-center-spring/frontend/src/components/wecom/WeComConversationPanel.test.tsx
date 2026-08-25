import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { WeComConversationPanel } from './WeComConversationPanel';

vi.mock('./WeComTimelineSegment', () => ({
  WeComTimelineSegment: () => <div data-testid="wecom-segment" />,
}));

describe('WeComConversationPanel', () => {
  it('offers an independent selector for each WeCom source conversation', () => {
    render(
      <WeComConversationPanel
        contactPointId="wecom:external-1"
        items={[]}
        conversations={[
          { id: 'group-a', type: 'GROUP', displayName: '群 A', contactPointId: 'wecom:external-1', items: [{ sourceId: 'a' }] as never[] },
          { id: 'group-b', type: 'GROUP', displayName: '群 B', contactPointId: 'wecom:external-1', items: [{ sourceId: 'b' }] as never[] },
        ]}
        viewer={{ prepareSegment: vi.fn(), reportComponentError: vi.fn() }}
      />,
    );

    expect(screen.getByRole('button', { name: '群 A' })).toBeVisible();
    expect(screen.getByRole('button', { name: '群 B' })).toBeVisible();
  });

  it('fills the timeline and exposes only the native client action', () => {
    const { getByTestId } = render(
      <WeComConversationPanel
        contactPointId="wecom:external-1"
        items={[{ sourceId: 'm1' } as never]}
        viewer={{ prepareSegment: vi.fn(), reportComponentError: vi.fn() }}
      />,
    );
    const root = getByTestId('wecom-conversation-page');
    expect(root).toHaveStyle({ width: '100%', height: '100%', minHeight: '0' });
    expect(screen.getByTestId('wecom-open-client-link')).toHaveTextContent('在企业微信中打开');
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });
});
