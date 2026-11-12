import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
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

  it('loads only while visible and releases the blob after leaving the viewport', async () => {
    let notifyIntersection: IntersectionObserverCallback = () => undefined;
    class IntersectionObserverStub {
      constructor(callback: IntersectionObserverCallback) {
        notifyIntersection = callback;
      }
      observe() {}
      unobserve() {}
      disconnect() {}
      takeRecords() { return []; }
      root = null;
      rootMargin = '';
      thresholds = [];
    }
    vi.stubGlobal('IntersectionObserver', IntersectionObserverStub);
    const revokeObjectUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    fetchMediaUrl.mockResolvedValue('blob:photo');

    render(<MessageBubble message={message()} isActive={false} onClick={() => undefined} />);
    expect(fetchMediaUrl).not.toHaveBeenCalled();

    act(() => {
      notifyIntersection([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver);
    });
    expect(await screen.findByRole('img', { name: 'photo.png' })).not.toBeNull();

    act(() => {
      notifyIntersection([{ isIntersecting: false } as IntersectionObserverEntry], {} as IntersectionObserver);
    });
    await waitFor(() => expect(revokeObjectUrl).toHaveBeenCalledWith('blob:photo'));
    expect(screen.queryByRole('img', { name: 'photo.png' })).toBeNull();
  });

  it('releases and falls back when the browser cannot decode the image', async () => {
    const revokeObjectUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    fetchMediaUrl.mockResolvedValue('blob:broken');
    render(<MessageBubble message={message()} isActive={false} onClick={() => undefined} />);

    const image = await screen.findByRole('img', { name: 'photo.png' });
    fireEvent.error(image);

    expect(await screen.findByText('photo.png')).not.toBeNull();
    await waitFor(() => expect(revokeObjectUrl).toHaveBeenCalledWith('blob:broken'));
  });
});

describe('MessageBubble email text', () => {
  it('decodes HTML entities in legacy plain-text email bodies', () => {
    render(<MessageBubble message={{
      ...message(),
      id: 'email-1',
      kind: 'email',
      channelType: 'email',
      bodyText: '品名：LED&nbsp;灯带&nbsp;&amp;&nbsp;数量：800',
      bodyHtml: '',
      attachments: [],
    }} isActive={false} onClick={() => undefined} />);

    expect(screen.getByText('品名：LED 灯带 & 数量：800')).toBeInTheDocument();
    expect(screen.queryByText(/&nbsp;/)).not.toBeInTheDocument();
  });
});
