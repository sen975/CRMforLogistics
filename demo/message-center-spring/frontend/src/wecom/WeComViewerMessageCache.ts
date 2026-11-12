import type { WeComViewerMessage } from '../api/types';

const DEFAULT_MAX_ENTRIES = 512;
const DEFAULT_TTL_MS = 15 * 60 * 1000;

interface CacheEntry {
  message: WeComViewerMessage;
  expiresAt: number;
  lastUsed: number;
}

export interface WeComViewerMessageCacheOptions {
  maxEntries?: number;
  ttlMs?: number;
  now?: () => number;
}

export class WeComViewerMessageCache {
  private readonly entries = new Map<string, CacheEntry>();
  private readonly maxEntries: number;
  private readonly ttlMs: number;
  private readonly now: () => number;
  private sequence = 0;

  constructor(options: WeComViewerMessageCacheOptions = {}) {
    this.maxEntries = Math.max(1, Math.floor(options.maxEntries ?? DEFAULT_MAX_ENTRIES));
    this.ttlMs = Math.max(1, Math.floor(options.ttlMs ?? DEFAULT_TTL_MS));
    this.now = options.now ?? (() => Date.now());
  }

  get(viewerAuthToken: string, contactPointId: string, msgid: string): WeComViewerMessage | undefined {
    this.removeExpired();
    const key = cacheKey(viewerAuthToken, contactPointId, msgid);
    const entry = this.entries.get(key);
    if (!entry) return undefined;
    entry.lastUsed = ++this.sequence;
    return entry.message;
  }

  set(viewerAuthToken: string, contactPointId: string, message: WeComViewerMessage): void {
    if (!viewerAuthToken || !contactPointId || !message.msgid || !message.secretKey) return;
    this.removeExpired();
    const now = this.now();
    this.entries.set(cacheKey(viewerAuthToken, contactPointId, message.msgid), {
      message,
      expiresAt: now + this.ttlMs,
      lastUsed: ++this.sequence,
    });
    this.evictToLimit();
  }

  setMany(viewerAuthToken: string, contactPointId: string, messages: WeComViewerMessage[]): void {
    for (const message of messages) this.set(viewerAuthToken, contactPointId, message);
  }

  clearToken(viewerAuthToken: string): void {
    const prefix = `${viewerAuthToken}\0`;
    for (const key of this.entries.keys()) {
      if (key.startsWith(prefix)) this.entries.delete(key);
    }
  }

  clearAll(): void {
    this.entries.clear();
    this.sequence = 0;
  }

  size(): number {
    this.removeExpired();
    return this.entries.size;
  }

  private removeExpired(): void {
    const now = this.now();
    for (const [key, entry] of this.entries) {
      if (now >= entry.expiresAt) this.entries.delete(key);
    }
  }

  private evictToLimit(): void {
    while (this.entries.size > this.maxEntries) {
      const oldest = [...this.entries.entries()]
        .sort((left, right) => left[1].lastUsed - right[1].lastUsed)[0];
      if (!oldest) return;
      this.entries.delete(oldest[0]);
    }
  }
}

function cacheKey(viewerAuthToken: string, contactPointId: string, msgid: string): string {
  return `${viewerAuthToken}\0${contactPointId}\0${msgid}`;
}
