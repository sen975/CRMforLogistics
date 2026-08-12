import { isAxiosError } from 'axios';
import type { TemplateMediaAsset, TemplateMediaAssetStatus } from '../../api/types';

type RecoveryOptions = {
  clientRequestId: string;
  upload: (clientRequestId: string, signal?: AbortSignal) => Promise<TemplateMediaAsset>;
  find: (clientRequestId: string, signal?: AbortSignal) => Promise<TemplateMediaAsset>;
  intervalMs?: number;
  maxPolls?: number;
  signal?: AbortSignal;
};

export class MediaUploadStateError extends Error {
  constructor(
    public readonly clientRequestId: string,
    public readonly assetStatus: TemplateMediaAssetStatus,
    public readonly exhausted: boolean,
  ) {
    super(exhausted ? 'Media upload is still processing' : `Media upload stopped in ${assetStatus}`);
  }
}

const isAvailable = (status: TemplateMediaAssetStatus) => status === 'UPLOADED' || status === 'ATTACHED';
const isTerminalFailure = (status: TemplateMediaAssetStatus) => (
  status === 'FAILED' || status === 'SUBMISSION_UNKNOWN'
  || status === 'ATTACHMENT_UNKNOWN' || status === 'ORPHANED'
);
function abortError(): DOMException {
  return new DOMException('Media upload recovery was aborted', 'AbortError');
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw abortError();
}

const delay = (milliseconds: number, signal?: AbortSignal) => new Promise<void>((resolve, reject) => {
  throwIfAborted(signal);
  const timeout = window.setTimeout(() => {
    signal?.removeEventListener('abort', onAbort);
    resolve();
  }, milliseconds);
  const onAbort = () => {
    window.clearTimeout(timeout);
    signal?.removeEventListener('abort', onAbort);
    reject(abortError());
  };
  signal?.addEventListener('abort', onAbort, { once: true });
});

export async function recoverTemplateMediaUpload({
  clientRequestId, upload, find, intervalMs = 2_000, maxPolls = 45, signal,
}: RecoveryOptions): Promise<TemplateMediaAsset> {
  throwIfAborted(signal);
  let current: TemplateMediaAsset | null = null;
  try {
    current = signal ? await upload(clientRequestId, signal) : await upload(clientRequestId);
  } catch (error) {
    throwIfAborted(signal);
    if (isAxiosError(error) && error.response) throw error;
  }

  throwIfAborted(signal);
  if (current && isAvailable(current.assetStatus)) return current;
  if (current && isTerminalFailure(current.assetStatus)) {
    throw new MediaUploadStateError(clientRequestId, current.assetStatus, false);
  }

  for (let attempt = 0; attempt < maxPolls; attempt += 1) {
    await delay(intervalMs, signal);
    throwIfAborted(signal);
    try {
      current = signal ? await find(clientRequestId, signal) : await find(clientRequestId);
    } catch (error) {
      throwIfAborted(signal);
      if (isAxiosError(error) && error.response) throw error;
      continue;
    }
    throwIfAborted(signal);
    if (isAvailable(current.assetStatus)) return current;
    if (isTerminalFailure(current.assetStatus)) {
      throw new MediaUploadStateError(clientRequestId, current.assetStatus, false);
    }
  }
  throw new MediaUploadStateError(clientRequestId, current?.assetStatus ?? 'PROCESSING', true);
}
