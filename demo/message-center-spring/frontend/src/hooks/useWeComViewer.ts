import { useCallback, useEffect, useMemo, useRef } from 'react';
import {
  bootstrapWeComViewer,
  createWeComViewerSession,
  createWeComViewerTargetSession,
  fetchWeComBinding,
  fetchWeComJsSdkConfig,
  fetchWeComViewerSession,
  recordWeComViewerEvent,
} from '../api/endpoints';
import type {
  WeComViewerMessage,
  WeComViewerTarget,
} from '../api/types';
import { useAuth } from './useAuth';
import { loadWeComViewerSdk, type WeComViewerSdk } from '../wecom/wecomSdk';
import { asWeComViewerError, WeComViewerError } from '../wecom/wecomErrors';
import { WeComViewerMessageCache } from '../wecom/WeComViewerMessageCache';

const WECOM_VIEWER_SESSION_BATCH_SIZE = 15;

export interface PreparedWeComSegment {
  sdk: WeComViewerSdk;
  viewerAuthToken: string;
  messages: WeComViewerMessage[];
  missingMessageIds?: string[];
  viewerSessionId?: string;
}

export interface WeComViewerHandle {
  prepareSegment: (
    contactPointId: string,
    messageIds: string[],
    signal?: AbortSignal,
  ) => Promise<PreparedWeComSegment>;
  prepareTargetSegment?: (
    target: WeComViewerTarget,
    messageIds: string[],
    signal?: AbortSignal,
  ) => Promise<PreparedWeComSegment>;
  reportComponentError: (viewerSessionId: string, viewerAuthToken: string) => Promise<void>;
}

export function useWeComViewer(): WeComViewerHandle {
  const { token: crmToken, wecomViewerAuthToken } = useAuth();
  const viewerTokenRef = useRef<string | null>(wecomViewerAuthToken);
  const bootstrapRef = useRef<Promise<string> | null>(null);
  const sdkRef = useRef<Promise<WeComViewerSdk> | null>(null);
  const messageCacheRef = useRef(new WeComViewerMessageCache());

  useEffect(() => {
    viewerTokenRef.current = wecomViewerAuthToken;
    bootstrapRef.current = null;
    sdkRef.current = null;
    messageCacheRef.current.clearAll();
  }, [crmToken, wecomViewerAuthToken]);

  const requireViewerToken = useCallback(async () => {
    if (viewerTokenRef.current) return viewerTokenRef.current;
    if (!bootstrapRef.current) {
      bootstrapRef.current = fetchWeComBinding()
        .then((binding) => {
          if (!binding.bound) throw new Error('账号尚未绑定企业微信');
          return bootstrapWeComViewer();
        })
        .then((response) => {
          viewerTokenRef.current = response.viewerAuthToken;
          return response.viewerAuthToken;
        })
        .catch((error) => {
          bootstrapRef.current = null;
          throw asWeComViewerError(error, 'viewer-token');
        });
    }
    return bootstrapRef.current;
  }, []);

  const requireSdk = useCallback(async (viewerAuthToken: string) => {
    if (!sdkRef.current) {
      const pageUrl = window.location.href.split('#')[0];
      sdkRef.current = Promise.all([
        loadWeComViewerSdk(),
        fetchWeComJsSdkConfig(pageUrl, viewerAuthToken),
      ]).then(async ([sdk, config]) => {
        if (!sdk.register || !sdk.initOpenData || !sdk.createOpenDataFrameFactory) {
          throw new Error('企业微信会话组件不可用');
        }
        sdk.register({
          corpId: config.corpId,
          agentId: config.agentId,
          jsApiList: config.jsApiList,
          getConfigSignature: async () => config.configSignature,
          getAgentConfigSignature: async () => config.agentConfigSignature,
        });
        await sdk.initOpenData();
        return sdk;
      }).catch((error) => {
        sdkRef.current = null;
        throw error instanceof WeComViewerError ? error : asWeComViewerError(error, 'sdk-init');
      });
    }
    return sdkRef.current;
  }, []);

  const prepareForTarget = useCallback(async (
    target: string | WeComViewerTarget,
    messageIds: string[],
    signal?: AbortSignal,
  ) => {
    throwIfAborted(signal);
    const cacheTarget = typeof target === 'string' ? target : `target:${target.targetType}:${target.targetId}`;
    if (typeof target === 'string' && !target.startsWith('wecom:')) throw new Error('联系人缺少企业微信身份');
    if (messageIds.length === 0 || messageIds.some((id) => !id)) {
      throw new Error('企业微信消息段缺少展示引用');
    }
    const viewerAuthToken = await requireViewerToken();
    throwIfAborted(signal);
    const uniqueMessageIds = [...new Set(messageIds)];
    const cachedMessages = new Map<string, WeComViewerMessage>();
    const missingMessageIds: string[] = [];
    for (const msgid of uniqueMessageIds) {
      const cached = messageCacheRef.current.get(viewerAuthToken, cacheTarget, msgid);
      if (cached) cachedMessages.set(msgid, cached);
      else missingMessageIds.push(msgid);
    }
    let sdk: WeComViewerSdk;
    try {
      sdk = await requireSdk(viewerAuthToken);
    } catch (error) {
      throw error instanceof WeComViewerError ? error : asWeComViewerError(error, 'sdk-init');
    }
    throwIfAborted(signal);
    let lastSessionId: string | undefined;
    for (const batch of chunk(missingMessageIds, WECOM_VIEWER_SESSION_BATCH_SIZE)) {
      throwIfAborted(signal);
      const batchResult = await loadMessageBatch(target, batch, viewerAuthToken, signal);
      lastSessionId = batchResult.viewerSessionId ?? lastSessionId;
      messageCacheRef.current.setMany(viewerAuthToken, cacheTarget, batchResult.messages);
      for (const message of batchResult.messages) cachedMessages.set(message.msgid, message);
    }
    throwIfAborted(signal);
    const orderedMessages = uniqueMessageIds
      .map((msgid) => cachedMessages.get(msgid))
      .filter((message): message is WeComViewerMessage => !!message);
    return {
      sdk,
      viewerAuthToken,
      messages: orderedMessages,
      missingMessageIds: uniqueMessageIds.filter((msgid) => !cachedMessages.has(msgid)),
      viewerSessionId: lastSessionId,
    };
  }, [requireSdk, requireViewerToken]);

  const prepareSegment = useCallback((contactPointId: string, messageIds: string[], signal?: AbortSignal) =>
    prepareForTarget(contactPointId, messageIds, signal), [prepareForTarget]);
  const prepareTargetSegment = useCallback((target: WeComViewerTarget, messageIds: string[], signal?: AbortSignal) =>
    prepareForTarget(target, messageIds, signal), [prepareForTarget]);

  const reportComponentError = useCallback(async (
    viewerSessionId: string,
    viewerAuthToken: string,
  ) => {
    try {
      await recordWeComViewerEvent(viewerSessionId, viewerAuthToken);
    } catch {
      // Viewer audit is best effort and must not replace the component error shown to the user.
    }
  }, []);

  return useMemo(() => ({ prepareSegment, prepareTargetSegment, reportComponentError }),
    [prepareSegment, prepareTargetSegment, reportComponentError]);
}

async function loadMessageBatch(
  target: string | WeComViewerTarget,
  messageIds: string[],
  viewerAuthToken: string,
  signal?: AbortSignal,
): Promise<{ viewerSessionId?: string; messages: WeComViewerMessage[] }> {
  try {
    const session = typeof target === 'string'
      ? await createWeComViewerSession(target, messageIds, viewerAuthToken, { signal })
      : await createWeComViewerTargetSession(target, messageIds, viewerAuthToken, { signal });
    const detail = await fetchWeComViewerSession(session.viewerSessionId, viewerAuthToken, { signal });
    return { viewerSessionId: detail.viewerSessionId, messages: detail.messages };
  } catch (error) {
    if (isAbortError(error) || signal?.aborted || messageIds.length === 1) {
      throw error instanceof WeComViewerError ? error : asWeComViewerError(error, 'session-load');
    }
    const results = await Promise.allSettled(
      messageIds.map((msgid) => loadMessageBatch(target, [msgid], viewerAuthToken, signal)),
    );
    const messages: WeComViewerMessage[] = [];
    let viewerSessionId: string | undefined;
    for (const result of results) {
      if (result.status !== 'fulfilled') continue;
      viewerSessionId = result.value.viewerSessionId ?? viewerSessionId;
      messages.push(...result.value.messages);
    }
    return { viewerSessionId, messages };
  }
}

function chunk<T>(values: readonly T[], size: number): T[][] {
  const result: T[][] = [];
  for (let index = 0; index < values.length; index += size) {
    result.push(values.slice(index, index + size));
  }
  return result;
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException('企业微信消息请求已取消', 'AbortError');
}
