import { ReloadOutlined, WarningOutlined } from '@ant-design/icons';
import { Button, Flex, Spin, Typography, theme } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { MessageResponse } from '../../api/types';
import type { WeComViewerTarget } from '../../api/types';
import type { WeComViewerHandle, PreparedWeComSegment } from '../../hooks/useWeComViewer';
import type { WeComOpenDataFrame, WeComOpenDataFrameOptions } from '../../wecom/wecomSdk';
import { asWeComViewerError, formatWeComViewerError, WeComViewerError } from '../../wecom/wecomErrors';
import { WeComFrameController } from '../../wecom/WeComFrameController';
import { wecomAvatarColor, wecomAvatarKind, wecomAvatarLetter, wecomPartyTypeLabel } from './wecomAvatar';

const { Text } = Typography;

export function WeComConversationFrame({
  contactPointId,
  target,
  items,
  viewer,
  reloadKey = 0,
}: {
  contactPointId: string;
  target?: WeComViewerTarget;
  items: MessageResponse[];
  viewer: WeComViewerHandle;
  reloadKey?: number;
}) {
  const { token } = theme.useToken();
  const hostRef = useRef<HTMLDivElement>(null);
  const frameRef = useRef<WeComOpenDataFrame | null>(null);
  const generationRef = useRef(0);
  const controllerRef = useRef(new WeComFrameController());
  const pendingUpdateRef = useRef<PendingFrameUpdate[]>([]);
  const [status, setStatus] = useState<'loading' | 'mounted' | 'failed'>('loading');
  const [failureMessage, setFailureMessage] = useState<string | null>(null);
  const [retryKey, setRetryKey] = useState(0);
  const rejectPendingUpdate = useCallback((error: unknown) => {
    for (const pending of pendingUpdateRef.current) {
      if (pending.cancelled) continue;
      pending.cancelled = true;
      pending.reject(error);
    }
  }, []);
  const discardPendingUpdate = useCallback(() => {
    for (const pending of pendingUpdateRef.current) {
      pending.cancelled = true;
      pending.cleanup();
    }
  }, []);
  const handleFrameUpdated = useCallback(() => {
    let pending = pendingUpdateRef.current.shift();
    while (pending?.cancelled) pending = pendingUpdateRef.current.shift();
    if (pending) pending.resolve();
  }, []);
  const waitForFrameUpdated = useCallback((signal?: AbortSignal) => {
    if (signal?.aborted) return Promise.reject(abortError());
    return new Promise<void>((resolve, reject) => {
      const onAbort = () => {
        pending.cancelled = true;
        cleanup();
        reject(abortError());
      };
      const cleanup = () => signal?.removeEventListener('abort', onAbort);
      const pendingResolve = () => { cleanup(); resolve(); };
      const pendingReject = (error: unknown) => { cleanup(); reject(error); };
      const pending: PendingFrameUpdate = {
        resolve: pendingResolve,
        reject: pendingReject,
        cleanup,
        cancelled: false,
      };
      pendingUpdateRef.current.push(pending);
      signal?.addEventListener('abort', onAbort, { once: true });
    });
  }, []);

  useEffect(() => {
    const generation = ++generationRef.current;
    const controller = new AbortController();
    let disposed = false;
    setStatus('loading');
    setFailureMessage(null);

    const isCurrent = () => !disposed && !controller.signal.aborted && generationRef.current === generation;
    const fail = (error: unknown, prepared?: PreparedWeComSegment) => {
      if (!isCurrent()) return;
      rejectPendingUpdate(error);
      const viewerError = error instanceof WeComViewerError
        ? error
        : asWeComViewerError(error, 'frame-update');
      setFailureMessage(formatWeComViewerError(viewerError));
      setStatus('failed');
      if (prepared) {
        void viewer.reportComponentError(prepared.viewerSessionId, prepared.viewerAuthToken, {
          stage: viewerError.stage,
          generation,
          errorCategory: viewerError.code || 'SDK_RESULT_FAILURE',
        });
      }
    };

    const run = async () => {
      try {
        if (frameRef.current) {
          await controllerRef.current.update(frameRef.current, { msgList: [] }, generation,
            () => generationRef.current, controller.signal, waitForFrameUpdated);
        }
        const messageIds = items.map((item) => item.sourceId || '').filter(Boolean);
        if (!messageIds.length) throw new WeComViewerError('session-create', '企业微信消息段缺少展示引用');
        const prepared = target && viewer.prepareTargetSegment
          ? await viewer.prepareTargetSegment(target, messageIds, controller.signal)
          : await viewer.prepareSegment(contactPointId, messageIds, controller.signal);
        if (!isCurrent() || !hostRef.current) return;

        if (!frameRef.current) {
          const factory = prepared.sdk.createOpenDataFrameFactory?.();
          if (!factory) throw new WeComViewerError('frame-create', '企业微信会话组件不可用');
          const frame = factory.createOpenDataFrame(frameOptions(
            hostRef.current,
            token,
            () => { if (isCurrent()) setStatus('mounted'); },
            (error) => fail(error, prepared),
            handleFrameUpdated,
            (error) => fail(error, prepared),
          ));
          frame.el.style.setProperty('display', 'block');
          frame.el.style.setProperty('width', '100%');
          frame.el.style.setProperty('height', '100%');
          frame.el.style.setProperty('min-width', '0');
          frame.el.style.setProperty('min-height', '0');
          frame.el.style.setProperty('border', '0');
          frameRef.current = frame;
        }

        const itemsBySourceId = new Map(
          items
            .map((item) => [item.sourceId, item] as const)
            .filter(([sourceId]) => !!sourceId),
        );
        // The viewer can normalize message IDs while preserving request order.
        // Keep exact source-ID matches first, then pair normalized results by that order.
        const orderedItems = messageIds
          .map((msgid) => itemsBySourceId.get(msgid))
          .filter((item): item is MessageResponse => !!item);
        const canUsePositionalFallback = prepared.messages.length === messageIds.length;
        const showSenderName = target?.targetType === 'WECOM_GROUP';
        const groupSenderLabels = showSenderName ? buildGroupSenderLabels(items) : new Map<string, string>();
        const msgList = prepared.messages
          .map((message, index) => {
            const item = itemsBySourceId.get(message.msgid)
              || (canUsePositionalFallback ? orderedItems[index] : undefined);
            const baseMessage: WeComFrameMessage = {
              msgid: message.msgid,
              secretKey: message.secretKey,
              direction: item?.direction === 'outbound' ? 'outbound' : 'inbound',
              senderAvatarUrl: item?.sender?.avatarUrl || '',
              senderAvatarText: wecomAvatarLetter(item?.sender?.displayName || '未获取昵称'),
              senderAvatarKind: wecomAvatarKind(item?.sender?.partyType),
              occurredAt: formatGroupMessageTime(item?.occurredAt || ''),
            };
            if (!showSenderName) return baseMessage;
            return {
              ...baseMessage,
              senderDisplayName: item
                ? groupSenderLabels.get(senderIdentity(item)) || '未获取昵称'
                : '未获取昵称',
            };
          })
          .filter((message): message is WeComFrameMessage => !!message);

        if (prepared.missingMessageIds?.length) {
          setFailureMessage(`部分消息暂时无法读取（${prepared.missingMessageIds.length} 条）`);
        }
        const frame = frameRef.current;
        if (!frame) throw new WeComViewerError('frame-create', '企业微信会话组件不可用');
        const frameData = {
          showGroupHeader: target?.targetType === 'WECOM_GROUP' && !!target.chatId,
          groupChatId: target?.targetType === 'WECOM_GROUP' && target.chatId ? target.chatId : '',
          showMessageMetadata: true,
          showSenderName,
          showDirectTime: !showSenderName,
          msgList,
        };
        await controllerRef.current.update(frame, frameData, generation,
          () => generationRef.current, controller.signal, waitForFrameUpdated);
        if (isCurrent() && prepared.sdk.createScrollViewContext) {
          const scrollView = await prepared.sdk.createScrollViewContext(frame, 'message-scroll');
          scrollView?.scrollTo({ top: Number.MAX_SAFE_INTEGER });
        }
        if (isCurrent()) setStatus('mounted');
      } catch (error) {
        if (isAbortError(error) || !isCurrent()) return;
        discardPendingUpdate();
        fail(error);
      }
    };

    void run();
    return () => {
      disposed = true;
      controller.abort();
      rejectPendingUpdate(abortError());
    };
  }, [contactPointId, target, items, reloadKey, retryKey, token, viewer,
    rejectPendingUpdate, discardPendingUpdate, handleFrameUpdated, waitForFrameUpdated]);

  useEffect(() => () => {
    frameRef.current?.dispose();
    frameRef.current = null;
  }, []);

  return (
    <div
      style={{ position: 'relative', width: '100%', height: '100%', minWidth: 0, minHeight: 0, overflow: 'hidden' }}
      data-testid="wecom-conversation-frame"
    >
      <div
        ref={hostRef}
        data-testid={status === 'mounted' ? 'wecom-conversation-frame-visible' : 'wecom-conversation-frame-host'}
        aria-hidden={status !== 'mounted'}
        style={{ width: '100%', height: '100%', minWidth: 0, minHeight: 0, visibility: status === 'mounted' ? 'visible' : 'hidden' }}
      />
      {status === 'loading' && (
        <Flex aria-label="企业微信消息加载中" align="center" justify="center" gap={8} style={{ position: 'absolute', inset: 0 }}>
          <Spin size="small" />
          <Text type="secondary" style={{ fontSize: 12 }}>企业微信消息加载中</Text>
        </Flex>
      )}
      {status === 'failed' && (
        <Flex aria-label="企业微信消息加载失败" align="center" gap={6} style={{ position: 'absolute', inset: 0, justifyContent: 'center' }}>
          <WarningOutlined style={{ color: token.colorWarning }} />
          <Text type="secondary" style={{ fontSize: 12 }}>
            企业微信消息加载失败{failureMessage ? `：${failureMessage}` : ''}
          </Text>
          <Button type="text" size="small" icon={<ReloadOutlined />} aria-label="重新加载企业微信消息" onClick={() => setRetryKey((value) => value + 1)} />
        </Flex>
      )}
      {status === 'mounted' && failureMessage && (
        <Text
          type="warning"
          role="status"
          style={{ position: 'absolute', top: 8, left: 12, right: 12, textAlign: 'center', fontSize: 12 }}
        >
          {failureMessage}
        </Text>
      )}
    </div>
  );
}

function frameOptions(
  host: HTMLElement,
  token: ReturnType<typeof theme.useToken>['token'],
  handleMounted: () => void,
  handleError: (error: unknown) => void,
  handleUpdated: () => void,
  handleMessageError: (error: unknown) => void,
): WeComOpenDataFrameOptions {
  return {
    el: host,
    template: `
      <view class="wecom-frame">
        <view wx:if="{{data.showGroupHeader}}" class="wecom-group-header">
          <span class="wecom-group-channel">企业微信 · 群聊</span>
          <ww-open-data type="chatName" openid="{{data.groupChatId}}" />
        </view>
        <scroll-view ref="message-scroll" scroll-y="true" class="wecom-scroll">
          <view wx:for="{{data.msgList}}" wx:key="msgid" class="wecom-row {{item.direction}}">
            <view class="wecom-entry">
              <view class="wecom-avatar-slot">
                <image wx:if="{{item.senderAvatarUrl}}" class="wecom-avatar" src="{{item.senderAvatarUrl}}" mode="aspectFill" />
                <view wx:else class="wecom-avatar wecom-avatar-fallback {{item.senderAvatarKind}}">{{item.senderAvatarText}}</view>
              </view>
              <view class="wecom-content">
                <view class="wecom-meta">
                  <span wx:if="{{data.showSenderName}}" class="wecom-sender">{{item.senderDisplayName}}</span>
                  <span class="wecom-time">{{item.occurredAt}}</span>
                </view>
                <view class="wecom-bubble">
                  <ww-open-message message-id="{{item.msgid}}" secret-key="{{item.secretKey}}" open-type="viewMessage" binderror="handleMessageError" />
                </view>
              </view>
            </view>
          </view>
        </scroll-view>
      </view>
    `,
    style: `
      .wecom-frame { display:flex; flex-direction:column; width:100%; height:100%; min-height:0; }
      .wecom-group-header { flex:0 0 auto; display:flex; flex-direction:column; gap:2px; padding:8px 12px; border-bottom:1px solid ${token.colorBorderSecondary}; background:${token.colorBgContainer}; }
      .wecom-group-channel { color:${token.colorTextSecondary}; font-size:11px; line-height:16px; }
      .wecom-group-header ww-open-data { color:${token.colorText}; font-size:14px; line-height:20px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
      .wecom-scroll { display:block; flex:1 1 auto; width:100%; height:auto; min-height:0; }
      .wecom-row { box-sizing:border-box; display:flex; width:100%; min-height:38px; padding:4px 10px; }
      .wecom-row.inbound { justify-content:flex-start; }
      .wecom-row.outbound { justify-content:flex-end; }
      .wecom-entry { display:flex; align-items:flex-start; gap:8px; max-width:82%; min-width:0; }
      .wecom-row.outbound .wecom-entry { flex-direction:row-reverse; }
      .wecom-avatar-slot { flex:0 0 28px; width:28px; height:28px; }
      .wecom-avatar { display:flex; align-items:center; justify-content:center; box-sizing:border-box; width:28px; height:28px; border-radius:50%; }
      .wecom-avatar-fallback { color:#fff; font-size:13px; }
      .wecom-avatar-fallback.employee { background:${wecomAvatarColor('EMPLOYEE')}; }
      .wecom-avatar-fallback.external { background:${wecomAvatarColor('EXTERNAL_CONTACT')}; }
      .wecom-avatar-fallback.robot { background:${wecomAvatarColor('ROBOT')}; }
      .wecom-avatar-fallback.unknown { background:${wecomAvatarColor('UNKNOWN')}; }
      .wecom-content { min-width:0; }
      .wecom-meta { display:flex; align-items:center; gap:8px; min-height:18px; margin-bottom:2px; color:${token.colorTextSecondary}; font-size:11px; line-height:16px; }
      .wecom-row.outbound .wecom-meta { justify-content:flex-end; }
      .wecom-sender { max-width:180px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; color:${token.colorText}; }
      .wecom-time { flex:none; }
      .wecom-direct-time { display:block; margin-top:2px; color:${token.colorTextSecondary}; font-size:11px; line-height:16px; }
      .wecom-row.outbound .wecom-direct-time { text-align:right; }
      .wecom-bubble { box-sizing:border-box; display:inline-block; max-width:78%; min-height:30px; padding:6px 10px; overflow:hidden; border:1px solid ${token.colorBorderSecondary}; border-radius:${token.borderRadiusSM}px; background:${token.colorBgContainer}; }
      .wecom-content .wecom-bubble { max-width:100%; }
      .wecom-row.outbound .wecom-bubble { border-color:#b7d4c6; background:#f4fbf7; }
    `,
    data: { msgList: [], showGroupHeader: false, groupChatId: '' },
    methods: { handleMessageError },
    handleMounted,
    handleUpdated,
    error: handleError,
    handleError,
  };
}

interface PendingFrameUpdate {
  resolve: () => void;
  reject: (error: unknown) => void;
  cleanup: () => void;
  cancelled: boolean;
}

interface WeComFrameMessage {
  msgid: string;
  secretKey: string;
  direction: 'inbound' | 'outbound';
  senderDisplayName?: string;
  senderAvatarUrl?: string;
  senderAvatarText: string;
  senderAvatarKind: ReturnType<typeof wecomAvatarKind>;
  occurredAt?: string;
}

function formatGroupMessageTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(date);
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((item) => item.type === type)?.value || '';
  return `${part('year')}-${part('month')}-${part('day')} ${part('hour')}:${part('minute')}`;
}

function buildGroupSenderLabels(items: MessageResponse[]): Map<string, string> {
  const senders = new Map<string, { displayName: string; partyType: string }>();
  items.forEach((item, index) => {
    const identity = senderIdentity(item, index);
    if (senders.has(identity)) return;
    senders.set(identity, {
      displayName: item.sender?.displayName?.trim() || '',
      partyType: item.sender?.partyType || '',
    });
  });

  const namedCounts = new Map<string, number>();
  for (const sender of senders.values()) {
    if (sender.displayName) namedCounts.set(sender.displayName, (namedCounts.get(sender.displayName) || 0) + 1);
  }

  const labels = new Map<string, string>();
  const usedLabels = new Set<string>();
  let unnamedNumber = 0;
  for (const [identity, sender] of senders) {
    let label = sender.displayName;
    if (!label) {
      unnamedNumber += 1;
      label = `群成员 ${unnamedNumber}`;
    } else if ((namedCounts.get(sender.displayName) || 0) > 1) {
      label = `${label}（${wecomPartyTypeLabel(sender.partyType)}）`;
    }
    if (usedLabels.has(label)) {
      let suffix = 2;
      while (usedLabels.has(`${label} ${suffix}`)) suffix += 1;
      label = `${label} ${suffix}`;
    }
    usedLabels.add(label);
    labels.set(identity, label);
  }
  return labels;
}

function senderIdentity(item: MessageResponse, index = 0): string {
  return item.sender?.providerPartyId?.trim()
    || item.sender?.partyId?.trim()
    || `message:${item.sourceId || item.id || index}`;
}

function abortError(): DOMException {
  return new DOMException('企业微信消息渲染已取消', 'AbortError');
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}
