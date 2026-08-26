import { describe, expect, it, vi } from 'vitest';
import { WeComFrameController } from './WeComFrameController';
import type { WeComOpenDataFrame } from './wecomSdk';

describe('WeComFrameController', () => {
  it('serializes setData calls and skips stale generations', async () => {
    let release!: () => void;
    const calls: unknown[] = [];
    const frame = {
      setData: vi.fn((data: unknown) => {
        calls.push(data);
        if (calls.length === 1) return new Promise<void>((resolve) => { release = resolve; });
        return Promise.resolve();
      }),
    } as unknown as WeComOpenDataFrame;
    let generation = 1;
    const controller = new WeComFrameController();
    const first = controller.update(frame, { msgList: [] }, 1, () => generation);
    const second = controller.update(frame, { msgList: [{ msgid: 'b' }] }, 2, () => generation);
    await Promise.resolve();
    generation = 3;
    release();
    await Promise.all([first, second]);
    expect(frame.setData).toHaveBeenCalledTimes(1);
    expect(calls).toEqual([{ msgList: [] }]);
  });
});
