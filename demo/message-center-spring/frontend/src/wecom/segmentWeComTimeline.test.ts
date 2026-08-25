import { describe, expect, it } from 'vitest';
import type { MessageResponse } from '../api/types';
import { segmentWeComTimeline } from './segmentWeComTimeline';

function message(id: string, channelType = 'wecom'): MessageResponse {
  return {
    id,
    sourceId: id,
    direction: 'inbound',
    kind: 'text',
    subject: '',
    bodyText: channelType === 'wecom' ? '' : id,
    bodyHtml: '',
    channelType,
    from: 'sender',
    to: 'receiver',
    occurredAt: `2026-08-17T00:00:${id.padStart(2, '0')}Z`,
    status: 'received',
    ingestSequence: Number(id) || 0,
    attachments: [],
  };
}

function messages(count: number): MessageResponse[] {
  return Array.from({ length: count }, (_, index) => message(String(index + 1)));
}

describe('segmentWeComTimeline', () => {
  it.each([
    [15, [5, 5, 5]],
    [16, [5, 5, 6]],
    [17, [5, 6, 6]],
    [18, [6, 6, 6]],
  ])('balances %i mixed WeCom messages', (count, expected) => {
    const segments = segmentWeComTimeline(messages(count), 'mixed')
      .filter((block) => block.kind === 'wecom');

    expect(segments.map((block) => block.items.length)).toEqual(expected);
  });

  it('breaks segments at every non-WeCom timeline item', () => {
    const blocks = segmentWeComTimeline([
      message('1'),
      message('2', 'email'),
      message('3'),
    ], 'mixed');

    expect(blocks).toHaveLength(3);
    expect(blocks.map((block) => block.kind)).toEqual(['wecom', 'item', 'wecom']);
  });

  it('keeps only the latest 15 WeCom messages for a standalone thread', () => {
    const blocks = segmentWeComTimeline(messages(18), 'standalone');

    expect(blocks).toHaveLength(1);
    expect(blocks[0].kind).toBe('wecom');
    if (blocks[0].kind === 'wecom') {
      expect(blocks[0].items.map((item) => item.sourceId)).toEqual(
        messages(18).slice(-15).map((item) => item.sourceId),
      );
    }
  });
});
