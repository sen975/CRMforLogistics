import { describe, expect, it, vi } from 'vitest';
import { WeComFrameRegistry } from './WeComFrameRegistry';

describe('WeComFrameRegistry', () => {
  it('removes the iframe when a mounted frame is released', async () => {
    const registry = new WeComFrameRegistry();
    const iframe = document.createElement('iframe');
    document.body.appendChild(iframe);
    const frame = { el: iframe, dispose: vi.fn() };

    await registry.enqueue('contact-a:segment-1', 'wecom:contact-a', () => frame);
    expect(iframe.isConnected).toBe(true);

    registry.release('contact-a:segment-1');

    expect(frame.dispose).toHaveBeenCalledOnce();
    expect(iframe.isConnected).toBe(false);
  });

  it('cancels queued work for a released key before it creates a frame', async () => {
    const registry = new WeComFrameRegistry();
    const resolvers: Array<(frame: { el: HTMLIFrameElement; dispose: () => void }) => void> = [];
    const blockers = Array.from({ length: 4 }, (_, index) => registry.enqueue(
      `contact-a:blocker-${index}`,
      'wecom:contact-a',
      () => new Promise((resolve) => { resolvers.push(resolve); }),
    ));
    let createQueued = false;
    const queued = registry.enqueue('contact-a:segment-2', 'wecom:contact-a', () => {
      createQueued = true;
      return { el: document.createElement('iframe'), dispose: vi.fn() };
    });

    registry.release('contact-a:segment-2');

    await expect(queued).rejects.toMatchObject({ name: 'AbortError' });
    expect(createQueued).toBe(false);
    resolvers.forEach((resolve) => resolve({ el: document.createElement('iframe'), dispose: vi.fn() }));
    await Promise.all(blockers);
  });
});
