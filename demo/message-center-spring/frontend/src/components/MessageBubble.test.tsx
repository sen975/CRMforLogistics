import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import MessageBubble from './MessageBubble';
import type { MessageResponse } from '../api/types';

const fetchMediaUrl = vi.fn();

vi.mock('../api/endpoints', () => ({
  fetchMediaUrl: (...args: unknown[]) => fetchMediaUrl(...args),
}));

function message(): MessageResponse {
  return {
    id: 'message-1',
    direction: 'outbound',
    kind: 'image',
    subject: '',
    bodyText: '[image]',
    bodyHtml: '',
    channelType: 'chatapp',
    from: 'business',
    to: 'customer',
    occurredAt: '2026-08-11T07:16:02Z',
    status: 'submitted',
    ingestSequence: 1,
    attachments: [{
      id: 'attachment-1',
      mediaKind: 'image',
      mimeType: 'image/png',
      fileName: 'photo.png',
      sizeBytes: 123,
    }],
  };
}

describe('MessageBubble media', () => {
  beforeEach(() => {
    fetchMediaUrl.mockReset();
  });

  it('renders an authenticated image attachment as a real image', async () => {
    fetchMediaUrl.mockResolvedValue('blob:photo');

    render(<MessageBubble message={message()} isActive={false} onClick={() => undefined} />);

    const image = await screen.findByRole('img', { name: 'photo.png' });
    expect(image.getAttribute('src')).toBe('blob:photo');
  });

  it('falls back to the image label when media loading fails', async () => {
    fetchMediaUrl.mockRejectedValue(new Error('forbidden'));

    render(<MessageBubble message={message()} isActive={false} onClick={() => undefined} />);

    await waitFor(() => expect(fetchMediaUrl).toHaveBeenCalledWith('attachment-1'));
    expect(await screen.findByText('photo.png')).not.toBeNull();
  });
});
