import type { WeComOpenDataFrame } from './wecomSdk';

/** Serializes clear/fill writes to the single mounted OpenDataFrame. */
export class WeComFrameController {
  private tail: Promise<void> = Promise.resolve();

  update(
    frame: WeComOpenDataFrame,
    data: Record<string, unknown>,
    generation: number,
    currentGeneration: () => number,
    signal?: AbortSignal,
    waitForUpdated?: (signal?: AbortSignal) => Promise<void>,
  ): Promise<void> {
    const operation = this.tail.then(async () => {
      if (signal?.aborted || currentGeneration() !== generation) return;
      if (!frame.setData) throw new Error('当前企业微信 SDK 不支持更新会话内容');
      const updated = waitForUpdated ? observe(withTimeout(waitForUpdated(signal))) : undefined;
      await withTimeout(frame.setData(data));
      if (updated) {
        const result = await updated;
        if (!result.ok) throw result.error;
      }
      if (signal?.aborted || currentGeneration() !== generation) return;
    });
    this.tail = operation.catch(() => undefined);
    return operation;
  }

  reset(): void {
    this.tail = Promise.resolve();
  }
}

function observe(operation: Promise<void>): Promise<{ ok: true } | { ok: false; error: unknown }> {
  return operation.then(
    () => ({ ok: true }),
    (error: unknown) => ({ ok: false, error }),
  );
}

function withTimeout(operation: Promise<void>, timeoutMs = 15_000): Promise<void> {
  return new Promise<void>((resolve, reject) => {
    const timer = window.setTimeout(() => reject(new Error('企业微信组件更新超时')), timeoutMs);
    operation.then(
      () => { window.clearTimeout(timer); resolve(); },
      (error) => { window.clearTimeout(timer); reject(error); },
    );
  });
}
