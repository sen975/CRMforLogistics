import type { MessageResponse } from '../api/types';

export type WeComTimelineMode = 'standalone' | 'mixed';

export interface WeComTimelineBlock<T = MessageResponse> {
  kind: 'wecom';
  id: string;
  items: T[];
}

export interface PlainTimelineBlock<T> {
  kind: 'item';
  id: string;
  item: T;
}

export type TimelineBlock<T> = WeComTimelineBlock<T> | PlainTimelineBlock<T>;

type TimelineValue = {
  id: string;
  channelType?: string;
  sourceId?: string | null;
};

function balancedSizes(total: number, maximum: number): number[] {
  if (total <= 0) return [];
  const groups = Math.ceil(total / maximum);
  const base = Math.floor(total / groups);
  const largerGroups = total % groups;
  return Array.from(
    { length: groups },
    (_, index) => base + (index >= groups - largerGroups ? 1 : 0),
  );
}

function signature(items: readonly TimelineValue[]): string {
  const value = items.map((item) => item.sourceId || item.id).join('|');
  let hash = 2166136261;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return `wecom-segment-${(hash >>> 0).toString(16).padStart(8, '0')}`;
}

export function segmentWeComTimeline<T extends TimelineValue>(
  items: readonly T[],
  mode: WeComTimelineMode,
): TimelineBlock<T>[] {
  if (mode === 'standalone') {
    const latest = items.filter((item) => item.channelType === 'wecom').slice(-15);
    return latest.length ? [{ kind: 'wecom', id: signature(latest), items: latest }] : [];
  }

  const result: TimelineBlock<T>[] = [];
  let run: T[] = [];
  const flush = () => {
    let offset = 0;
    for (const size of balancedSizes(run.length, 6)) {
      const group = run.slice(offset, offset + size);
      result.push({ kind: 'wecom', id: signature(group), items: group });
      offset += size;
    }
    run = [];
  };

  for (const item of items) {
    if (item.channelType === 'wecom') {
      run.push(item);
      continue;
    }
    flush();
    result.push({ kind: 'item', id: `timeline-item-${item.id}`, item });
  }
  flush();
  return result;
}
