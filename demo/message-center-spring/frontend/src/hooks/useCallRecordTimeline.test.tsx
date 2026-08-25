import type { ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useCallRecordTimeline } from './useCallRecordTimeline';

const fetchTimeline = vi.fn();

vi.mock('../api/endpoints', () => ({
  fetchTimeline: (...args: unknown[]) => fetchTimeline(...args),
}));

function timeline(id: string, state = 'completed') {
  return {
    items: [{
      type: 'callRecord',
      occurredAt: '2026-08-11T08:00:00Z',
      sortId: id,
      payload: {
        id,
        direction: 'outbound',
        phonePointId: 'phone-1',
        durationSeconds: 12,
        state,
        errorCode: '',
        errorMessage: '',
        errorRetryable: false,
        attempts: 0,
      },
    }],
    nextCursor: '',
    itemCount: 1,
    threadRevision: id,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

function wrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { gcTime: 0 } },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

afterEach(() => {
  vi.useRealTimers();
  fetchTimeline.mockReset();
});

describe('useCallRecordTimeline', () => {
  it('does not let an old contact response overwrite the selected contact', async () => {
    const first = deferred<ReturnType<typeof timeline>>();
    const second = deferred<ReturnType<typeof timeline>>();
    fetchTimeline.mockImplementation((contactId: string) => (
      contactId === 'contact-a' ? first.promise : second.promise
    ));

    const { result, rerender } = renderHook(
      ({ contactId }) => useCallRecordTimeline(contactId),
      { initialProps: { contactId: 'contact-a' }, wrapper: wrapper() },
    );
    rerender({ contactId: 'contact-b' });

    await act(async () => { second.resolve(timeline('record-b')); });
    await waitFor(() => expect(result.current.records[0]?.id).toBe('record-b'));
    await act(async () => { first.resolve(timeline('record-a')); });
    expect(result.current.records[0]?.id).toBe('record-b');
  });

  it('retries a transient first timeline failure', async () => {
    fetchTimeline
      .mockRejectedValueOnce(new Error('temporary'))
      .mockResolvedValue(timeline('record-1', 'queued'));

    const { result } = renderHook(
      () => useCallRecordTimeline('contact-a'),
      { wrapper: wrapper() },
    );

    await waitFor(() => expect(result.current.records[0]?.id).toBe('record-1'));
    expect(fetchTimeline).toHaveBeenCalledTimes(2);
  });

  it('polls active records and stops after a terminal response', async () => {
    vi.useFakeTimers();
    const terminal = deferred<ReturnType<typeof timeline>>();
    fetchTimeline
      .mockResolvedValueOnce(timeline('record-1', 'queued'))
      .mockReturnValueOnce(terminal.promise);

    const { result } = renderHook(
      () => useCallRecordTimeline('contact-a'),
      { wrapper: wrapper() },
    );
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(result.current.records[0]?.state).toBe('queued');

    await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
    expect(fetchTimeline).toHaveBeenCalledTimes(2);

    await act(async () => {
      terminal.resolve(timeline('record-1', 'completed'));
      await terminal.promise;
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.records[0]?.state).toBe('completed');

    await act(async () => { await vi.advanceTimersByTimeAsync(4000); });
    expect(fetchTimeline).toHaveBeenCalledTimes(2);
  });
});
