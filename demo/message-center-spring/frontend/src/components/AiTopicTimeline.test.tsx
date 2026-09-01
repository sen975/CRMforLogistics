import '@testing-library/jest-dom/vitest';
import { fireEvent, render, screen } from '@testing-library/react';
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
  it('requires two selected topics and confirmation before merging', () => {
    const merge = vi.fn();
    render(<AiTopicTimeline contactId="c1" timeline={{ contactId: 'c1', generation: { status: 'READY', jobId: null, errorCode: null, updatedAt: null }, topics: [topic('1', '报价'), topic('2', '时效')], weComUnsupported: false }} onSourceClick={vi.fn()} actions={{ update: { mutate: vi.fn() } as any, merge: { mutate: merge } as any, retry: { mutate: vi.fn() } as any }} />);
    const checkboxes = screen.getAllByRole('checkbox');
    expect(screen.getByRole('button', { name: /合并 Topic/ })).toBeDisabled();
    fireEvent.click(checkboxes[0]); fireEvent.click(checkboxes[1]);
    const mergeButton = screen.getByRole('button', { name: /合并 Topic/ });
    expect(mergeButton).toBeEnabled(); fireEvent.click(mergeButton);
    expect(screen.getByText('确认合并')).toBeInTheDocument(); fireEvent.click(screen.getByText('确认合并'));
    expect(merge).toHaveBeenCalledOnce();
  });

  it('shows explicit unsupported state for WeCom-only contacts', () => {
    render(<AiTopicTimeline contactId="c1" timeline={{ contactId: 'c1', generation: { status: 'NOT_STARTED', jobId: null, errorCode: 'WECOM_AI_UNSUPPORTED', updatedAt: null }, topics: [], weComUnsupported: true }} onSourceClick={vi.fn()} />);
    expect(screen.getByText('当前渠道暂不支持 AI Topic 总结')).toBeInTheDocument();
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
});
