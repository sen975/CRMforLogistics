import '@testing-library/jest-dom/vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import AiTopicTimeline from './AiTopicTimeline';

const topic = (id: string, title: string) => ({
  id, title, summary: `${title} 摘要`, summarySource: 'AI' as const,
  firstOccurredAt: '2026-08-01T00:00:00Z', lastOccurredAt: '2026-08-02T00:00:00Z',
  channels: ['email'], sourceCount: 1, version: 1,
  contactId: 'contact-1',
  sourceItems: [{ id: `message-${id}`, sourceType: 'MESSAGE' as const, occurredAt: '2026-08-01T00:00:00Z', channelType: 'email' as const }],
});

const topicWithChannels = (id: string, title: string, channels: string[]) => ({
  ...topic(id, title),
  channels,
});

describe('AiTopicTimeline', () => {
  it('lets the group Topic list fill the sidebar while keeping the title inset', () => {
    render(<AiTopicTimeline groupMode timeline={{
      sourceConversationId: 'group-1',
      generation: { status: 'READY', jobId: null, errorCode: null, updatedAt: null },
      topics: [topic('1', '报价')],
      weComUnsupported: false,
    }} />);

    expect(screen.getByTestId('ai-topic-timeline')).toHaveStyle({ padding: '16px 0' });
    expect(screen.getByTestId('ai-topic-timeline-toolbar')).toHaveStyle({ padding: '0 16px' });
  });

  it('allows a failed group Topic generation to be retried', () => {
    const retry = vi.fn();
    render(<AiTopicTimeline groupMode timeline={{
      sourceConversationId: 'group-1',
      generation: { status: 'FAILED', jobId: 'job-1', errorCode: 'AI_RESPONSE_INVALID', updatedAt: null },
      topics: [],
      weComUnsupported: false,
    }} actions={{ retry: { mutate: retry, isPending: false } as any }} />);

    fireEvent.click(screen.getByRole('button', { name: '重试' }));

    expect(retry).toHaveBeenCalledTimes(1);
  });

  it('requires two selected topics and an AI fusion preview before applying', async () => {
    const preview = vi.fn().mockResolvedValue({ previewId: 'preview-1', topicIds: ['1', '2'], title: '订单履约', summary: '融合报价与时效', sourceCount: 2, expectedVersions: { 1: 1, 2: 1 }, expiresAt: '2026-09-02T00:00:00Z' });
    const apply = vi.fn().mockResolvedValue(undefined);
    render(<AiTopicTimeline contactId="c1" timeline={{ contactId: 'c1', generation: { status: 'READY', jobId: null, errorCode: null, updatedAt: null }, topics: [topic('1', '报价'), topic('2', '时效')], weComUnsupported: false }} onSourceClick={vi.fn()} onPreviewFusion={preview} onApplyFusion={apply} actions={{ update: { mutate: vi.fn() } as any, retry: { mutate: vi.fn() } as any }} />);
    const checkboxes = screen.getAllByRole('checkbox');
    expect(screen.getByRole('button', { name: /合并 Topic/ })).toBeDisabled();
    fireEvent.click(checkboxes[0]); fireEvent.click(checkboxes[1]);
    const mergeButton = screen.getByRole('button', { name: /合并 Topic/ });
    expect(mergeButton).toBeEnabled(); fireEvent.click(mergeButton);
    await waitFor(() => expect(screen.getByText('订单履约')).toBeInTheDocument());
    expect(screen.getByText('融合报价与时效')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '确认融合' }));
    await waitFor(() => expect(apply).toHaveBeenCalledWith('preview-1'));
  });

  it('keeps WeCom-only contacts available before the first summary', () => {
    render(<AiTopicTimeline contactId="c1" timeline={{ contactId: 'c1', generation: { status: 'NOT_STARTED', jobId: null, errorCode: null, updatedAt: null }, topics: [], weComUnsupported: false }} onSourceClick={vi.fn()} />);
    expect(screen.getByText('暂无 Topic')).toBeInTheDocument();
    expect(screen.queryByText('当前渠道暂不支持 AI Topic 总结')).not.toBeInTheDocument();
  });

  it('keeps mixed-channel and WeCom-only topics in the timeline', () => {
    render(<AiTopicTimeline contactId="c1" timeline={{ contactId: 'c1', generation: { status: 'READY', jobId: null, errorCode: null, updatedAt: null }, topics: [
      topicWithChannels('mixed', '混合报价', ['email', 'wecom']),
      topicWithChannels('wecom-only', '企业微信闲聊', ['wecom']),
    ], weComUnsupported: false }} onSourceClick={vi.fn()} />);

    expect(screen.getByText('混合报价')).toBeInTheDocument();
    expect(screen.getByText('企业微信闲聊')).toBeInTheDocument();
  });

  it('keeps event time visible in collapsed topic headers and topic details', () => {
    render(<AiTopicTimeline contactId="c1" timeline={{ contactId: 'c1', generation: { status: 'READY', jobId: null, errorCode: null, updatedAt: null }, topics: [topic('1', '报价')], weComUnsupported: false }} onSourceClick={vi.fn()} />);
    expect(screen.getByText(/最近事件.*2026\/08\/02/)).toBeInTheDocument();
    fireEvent.click(screen.getByText('报价'));
    expect(screen.getByText('事件时间')).toBeInTheDocument();
    expect(screen.getByText(/首个事件.*2026\/08\/01/)).toBeInTheDocument();
  });

  it('never renders a raw WeCom group key in a referenced Topic label', () => {
    render(<AiTopicTimeline contactId="c1" timeline={{ contactId: 'c1', generation: { status: 'READY', jobId: null, errorCode: null, updatedAt: null }, topics: [{
      ...topic('group-topic', '员工到岗问候'),
      ownerType: 'WECOM_GROUP', ownerLabel: 'group:wriNTkcAAAJuDno3GEr6qeCTy5A', isReferencedGroupTopic: true,
    }], weComUnsupported: false }} onSourceClick={vi.fn()} />);

    expect(screen.getByText('外部群聊')).toBeInTheDocument();
    expect(screen.queryByText(/group:/)).not.toBeInTheDocument();
  });

  it('shows the pending origin and original topic without exposing its id', () => {
    render(<AiTopicTimeline contactId="c1" timeline={{ contactId: 'c1', generation: { status: 'READY', jobId: null, errorCode: null, updatedAt: null }, topics: [], weComUnsupported: false }} pendingTopics={[{
      ...topic('pending-1', '待确认报价'),
      reviewOrigin: 'MERGE_SOURCE',
      reviewSourceTopicTitle: '历史报价',
    }]} onSourceClick={vi.fn()} />);

    expect(screen.getByText('联系人合并')).toBeInTheDocument();
    fireEvent.click(screen.getByText('待确认报价'));
    expect(screen.getByText('原 Topic：历史报价')).toBeInTheDocument();
    expect(screen.getByText(/时间范围.*2026\/08\/01.*2026\/08\/02/)).toBeInTheDocument();
    expect(screen.queryByText('pending-1')).not.toBeInTheDocument();
  });
});
