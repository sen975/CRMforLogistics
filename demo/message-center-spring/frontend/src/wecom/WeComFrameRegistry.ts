import type { WeComOpenDataFrame } from './wecomSdk';

export const WECOM_FRAME_LIMIT = 30;
export const WECOM_CONTACT_FRAME_LIMIT = 15;
export const WECOM_CACHED_CONTACT_LIMIT = 3;
export const WECOM_RENDER_QUEUE_LIMIT = 15;
export const WECOM_RENDER_CONCURRENCY = 4;

interface FrameEntry {
  key: string;
  contactPointId: string;
  frame: WeComOpenDataFrame;
  detailFrames: Set<HTMLIFrameElement>;
  lastUsed: number;
}

interface RenderJob {
  key: string;
  contactPointId: string;
  create: () => Promise<WeComOpenDataFrame>;
  resolve: (frame: WeComOpenDataFrame) => void;
  reject: (error: unknown) => void;
  cancelled: boolean;
}

export class WeComFrameRegistry {
  private readonly entries = new Map<string, FrameEntry>();
  private readonly contactUsage = new Map<string, number>();
  private readonly queue: RenderJob[] = [];
  private readonly activeJobSet = new Set<RenderJob>();
  private activeJobs = 0;
  private sequence = 0;

  enqueue(
    key: string,
    contactPointId: string,
    create: () => Promise<WeComOpenDataFrame> | WeComOpenDataFrame,
  ): Promise<WeComOpenDataFrame> {
    if (this.queue.length >= WECOM_RENDER_QUEUE_LIMIT) {
      return Promise.reject(new Error('企业微信消息渲染队列已满'));
    }
    return new Promise((resolve, reject) => {
      this.queue.push({
        key,
        contactPointId,
        create: () => Promise.resolve(create()),
        resolve,
        reject,
        cancelled: false,
      });
      this.pump();
    });
  }

  release(key: string): void {
    this.cancelQueued(key);
    this.activeJobsFor(key).forEach((job) => { job.cancelled = true; });
    this.releaseEntry(key);
  }

  touch(key: string): void {
    const entry = this.entries.get(key);
    if (!entry) return;
    entry.lastUsed = ++this.sequence;
    this.contactUsage.set(entry.contactPointId, entry.lastUsed);
  }

  registerDetailFrame(key: string, frame: HTMLIFrameElement): void {
    this.entries.get(key)?.detailFrames.add(frame);
  }

  reset(): void {
    for (const entry of this.entries.values()) this.destroyEntry(entry);
    this.entries.clear();
    this.contactUsage.clear();
    this.queue.splice(0).forEach((job) => job.reject(new Error('企业微信消息渲染已取消')));
    this.activeJobSet.forEach((job) => { job.cancelled = true; });
    this.activeJobSet.clear();
    this.activeJobs = 0;
  }

  private pump(): void {
    while (this.activeJobs < WECOM_RENDER_CONCURRENCY && this.queue.length > 0) {
      const job = this.queue.shift();
      if (!job) return;
      this.activeJobs += 1;
      this.activeJobSet.add(job);
      this.prepareCapacity(job.key, job.contactPointId);
      job.create()
        .then((frame) => {
          if (job.cancelled) {
            frame.dispose();
            frame.el.remove();
            throw abortError();
          }
          this.releaseEntry(job.key);
          const used = ++this.sequence;
          this.entries.set(job.key, {
            key: job.key,
            contactPointId: job.contactPointId,
            frame,
            detailFrames: new Set(),
            lastUsed: used,
          });
          this.contactUsage.set(job.contactPointId, used);
          job.resolve(frame);
        })
        .catch(job.reject)
        .finally(() => {
          this.activeJobs -= 1;
          this.activeJobSet.delete(job);
          this.pump();
        });
    }
  }

  private prepareCapacity(key: string, contactPointId: string): void {
    this.releaseEntry(key);
    this.contactUsage.set(contactPointId, ++this.sequence);
    while (this.contactUsage.size > WECOM_CACHED_CONTACT_LIMIT) {
      const oldest = [...this.contactUsage.entries()]
        .filter(([contact]) => contact !== contactPointId)
        .sort((left, right) => left[1] - right[1])[0];
      if (!oldest) break;
      this.destroyContact(oldest[0]);
    }
    while (this.contactEntries(contactPointId).length >= WECOM_CONTACT_FRAME_LIMIT) {
      this.evictOldest((entry) => entry.contactPointId === contactPointId);
    }
    while (this.entries.size >= WECOM_FRAME_LIMIT) this.evictOldest(() => true);
  }

  private contactEntries(contactPointId: string): FrameEntry[] {
    return [...this.entries.values()].filter((entry) => entry.contactPointId === contactPointId);
  }

  private destroyContact(contactPointId: string): void {
    for (const entry of this.contactEntries(contactPointId)) {
      this.destroyEntry(entry);
      this.entries.delete(entry.key);
    }
    this.contactUsage.delete(contactPointId);
  }

  private evictOldest(predicate: (entry: FrameEntry) => boolean): void {
    const entry = [...this.entries.values()]
      .filter(predicate)
      .sort((left, right) => left.lastUsed - right.lastUsed)[0];
    if (!entry) return;
    this.release(entry.key);
  }

  private destroyEntry(entry: FrameEntry): void {
    for (const detailFrame of entry.detailFrames) {
      detailFrame.src = 'about:blank';
      detailFrame.remove();
    }
    entry.detailFrames.clear();
    entry.frame.dispose();
    entry.frame.el.remove();
  }

  private releaseEntry(key: string): void {
    const entry = this.entries.get(key);
    if (!entry) return;
    this.destroyEntry(entry);
    this.entries.delete(key);
    if (![...this.entries.values()].some((item) => item.contactPointId === entry.contactPointId)) {
      this.contactUsage.delete(entry.contactPointId);
    }
  }

  private cancelQueued(key: string): void {
    const remaining: RenderJob[] = [];
    for (const job of this.queue) {
      if (job.key !== key) {
        remaining.push(job);
        continue;
      }
      job.cancelled = true;
      job.reject(abortError());
    }
    this.queue.splice(0, this.queue.length, ...remaining);
  }

  private activeJobsFor(key: string): RenderJob[] {
    return [...this.activeJobSet].filter((job) => job.key === key);
  }
}

export const weComFrameRegistry = new WeComFrameRegistry();

function abortError(): DOMException {
  return new DOMException('企业微信消息渲染已取消', 'AbortError');
}
