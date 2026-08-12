import { afterEach, describe, expect, it, vi } from 'vitest';
import type { TemplateMediaAsset, TemplateMediaAssetStatus } from '../../api/types';
import { recoverTemplateMediaUpload } from './templateMediaUpload';

function asset(clientRequestId: string, assetStatus: TemplateMediaAssetStatus): TemplateMediaAsset {
  return {
    id: 'asset-1',
    clientRequestId,
    format: 'IMAGE',
    contentType: 'image/png',
    sizeBytes: 1,
    sha256: '0'.repeat(64),
    providerUrl: assetStatus === 'UPLOADED' ? 'https://provider.invalid/a.png' : null,
    assetStatus,
    errorCode: null,
    errorMessage: null,
    traceId: 'trace-1',
  };
}

describe('recoverTemplateMediaUpload', () => {
  afterEach(() => vi.useRealTimers());

  it('polls with the same request id until the upload is available', async () => {
    vi.useFakeTimers();
    const upload = vi.fn().mockResolvedValue(asset('request-1', 'PROCESSING'));
    const find = vi.fn()
      .mockResolvedValueOnce(asset('request-1', 'PROCESSING'))
      .mockResolvedValueOnce(asset('request-1', 'UPLOADED'));

    const resultPromise = recoverTemplateMediaUpload({
      clientRequestId: 'request-1', upload, find, intervalMs: 2_000, maxPolls: 45,
    });
    await vi.advanceTimersByTimeAsync(4_000);

    await expect(resultPromise).resolves.toMatchObject({ assetStatus: 'UPLOADED' });
    expect(upload).toHaveBeenCalledTimes(1);
    expect(find).toHaveBeenNthCalledWith(1, 'request-1');
    expect(find).toHaveBeenNthCalledWith(2, 'request-1');
  });

  it.each(['FAILED', 'SUBMISSION_UNKNOWN', 'ATTACHMENT_UNKNOWN', 'ORPHANED'] as const)(
    'stops without retransmission on %s', async (status) => {
      const upload = vi.fn().mockResolvedValue(asset('request-2', status));
      const find = vi.fn();

      await expect(recoverTemplateMediaUpload({
        clientRequestId: 'request-2', upload, find, intervalMs: 1, maxPolls: 45,
      })).rejects.toMatchObject({ clientRequestId: 'request-2', assetStatus: status });
      expect(upload).toHaveBeenCalledTimes(1);
      expect(find).not.toHaveBeenCalled();
    },
  );

  it('stops polling when its signal is aborted', async () => {
    vi.useFakeTimers();
    const controller = new AbortController();
    const upload = vi.fn().mockResolvedValue(asset('request-abort', 'PROCESSING'));
    const find = vi.fn();
    const resultPromise = recoverTemplateMediaUpload({
      clientRequestId: 'request-abort', upload, find, intervalMs: 2_000, maxPolls: 45,
      signal: controller.signal,
    } as Parameters<typeof recoverTemplateMediaUpload>[0]);
    const assertion = expect(resultPromise).rejects.toMatchObject({ name: 'AbortError' });

    await vi.advanceTimersByTimeAsync(0);
    controller.abort();
    await vi.advanceTimersByTimeAsync(2_000);

    await assertion;
    expect(find).not.toHaveBeenCalled();
  });

  it('stops when a polling read receives an explicit HTTP error', async () => {
    vi.useFakeTimers();
    const upload = vi.fn().mockResolvedValue(asset('request-http-error', 'PROCESSING'));
    const httpError = { isAxiosError: true, response: { status: 409 } };
    const find = vi.fn().mockRejectedValue(httpError);
    const resultPromise = recoverTemplateMediaUpload({
      clientRequestId: 'request-http-error', upload, find, intervalMs: 2_000, maxPolls: 45,
    });
    const assertion = expect(resultPromise).rejects.toBe(httpError);

    await vi.advanceTimersByTimeAsync(2_000);

    await assertion;
    expect(find).toHaveBeenCalledTimes(1);
  });

  it('stops after 45 reads and retains the request id', async () => {
    vi.useFakeTimers();
    const upload = vi.fn().mockRejectedValue(new TypeError('network response lost'));
    const find = vi.fn().mockResolvedValue(asset('request-3', 'PROCESSING'));
    const resultPromise = recoverTemplateMediaUpload({
      clientRequestId: 'request-3', upload, find, intervalMs: 2_000, maxPolls: 45,
    });
    const assertion = expect(resultPromise).rejects.toMatchObject({
      clientRequestId: 'request-3', assetStatus: 'PROCESSING', exhausted: true,
    });
    await vi.advanceTimersByTimeAsync(90_000);
    await assertion;
    expect(upload).toHaveBeenCalledTimes(1);
    expect(find).toHaveBeenCalledTimes(45);
  });
});
