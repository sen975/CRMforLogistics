import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { WeComConversationPanel } from './WeComConversationPanel';

vi.mock('./WeComTimelineSegment', () => ({
  WeComTimelineSegment: () => <div data-testid="wecom-segment" />,
}));

describe('WeComConversationPanel', () => {
  it('renders one aggregated frame without a source conversation selector', () => {
    render(
      <WeComConversationPanel
        contactPointId="wecom:external-1"
        items={[]}
        viewer={{ prepareSegment: vi.fn(), reportComponentError: vi.fn() }}
      />,
    );

    expect(screen.queryByRole('button', { name: '群 A' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '群 B' })).not.toBeInTheDocument();
  });

  it('does not expose raw group keys in the related-group list', () => {
    render(
      <WeComConversationPanel
        contactPointId="wecom:contact-1"
        items={[]}
        viewer={{} as any}
        relatedGroups={[{
          sourceConversationId: 'group-1',
          displayName: 'group:raw-chat-id',
          avatarUrl: null,
          participantCount: 3,
        }]}
      />,
    );

    expect(screen.getByText('外部群聊')).toBeInTheDocument();
    expect(screen.queryByText(/group:/)).not.toBeInTheDocument();
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
    expect(screen.getByTestId('wecom-open-client-link')).toHaveAttribute('href', 'wxwork://');
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('renders related groups below the direct frame and opens the independent group route', async () => {
    const openGroup = vi.fn();
    render(
      <WeComConversationPanel
        contactPointId="wecom:external-1"
        items={[{ sourceId: 'm1' } as never]}
        viewer={{ prepareSegment: vi.fn(), reportComponentError: vi.fn() }}
        relatedGroups={[{
          sourceConversationId: 'group-1',
          displayName: '客户项目群',
          avatarUrl: null,
          participantCount: 4,
        }]}
        onOpenGroup={openGroup}
      />,
    );

    expect(screen.getByText('相关企业微信群')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /客户项目群/ })).toHaveTextContent('4 人');
    await userEvent.click(screen.getByRole('button', { name: /客户项目群/ }));
    expect(openGroup).toHaveBeenCalledWith('group-1');
  });

  it('does not expose a provider id when a participant profile is missing', async () => {
    const { rerender } = render(
      <WeComConversationPanel
        contactPointId="wecom:external-1"
        items={[]}
        viewer={{ prepareSegment: vi.fn(), reportComponentError: vi.fn() }}
      />,
    );
    expect(screen.queryByText('external-1')).not.toBeInTheDocument();
    rerender(
      <WeComConversationPanel
        contactPointId="wecom:external-1"
        items={[]}
        viewer={{ prepareSegment: vi.fn(), reportComponentError: vi.fn() }}
      />,
    );
  });
});
