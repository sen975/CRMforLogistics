import { ReloadOutlined, WarningOutlined } from '@ant-design/icons';
import { Button, Flex, Spin, Typography, theme } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { MessageResponse } from '../../api/types';
import type { WeComViewerTarget } from '../../api/types';
import type { WeComViewerHandle, PreparedWeComSegment } from '../../hooks/useWeComViewer';
import type { WeComOpenDataFrame, WeComOpenDataFrameOptions } from '../../wecom/wecomSdk';
import { asWeComViewerError, formatWeComViewerError, WeComViewerError } from '../../wecom/wecomErrors';
import { WeComFrameController } from '../../wecom/WeComFrameController';

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
  const [status, setStatus] = useState<'loading' | 'mounted' | 'failed'>('loading');
  const [failureMessage, setFailureMessage] = useState<string | null>(null);
  const [retryKey, setRetryKey] = useState(0);

  useEffect(() => {
    const generation = ++generationRef.current;
    const controller = new AbortController();
    let disposed = false;
    setStatus('loading');
    setFailureMessage(null);

    const isCurrent = () => !disposed && !controller.signal.aborted && generationRef.current === generation;
    const fail = (error: unknown, prepared?: PreparedWeComSegment) => {
      if (!isCurrent()) return;
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
            () => generationRef.current, controller.signal);
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
          ));
          frame.el.style.setProperty('display', 'block');
          frame.el.style.setProperty('width', '100%');
          frame.el.style.setProperty('height', '100%');
          frame.el.style.setProperty('min-width', '0');
          frame.el.style.setProperty('min-height', '0');
          frame.el.style.setProperty('border', '0');
          frameRef.current = frame;
        }

        const messageById = new Map(prepared.messages.map((message) => [message.msgid, message]));
        const msgList = messageIds
          .map((msgid) => {
            const message = messageById.get(msgid);
            if (!message) return null;
            const item = items.find((candidate) => candidate.sourceId === msgid);
            return {
              msgid: message.msgid,
              secretKey: message.secretKey,
              direction: item?.direction === 'outbound' ? 'outbound' : 'inbound',
            };
          })
          .filter((message): message is { msgid: string; secretKey: string; direction: 'inbound' | 'outbound' } => !!message);

        if (prepared.missingMessageIds?.length) {
          setFailureMessage(`部分消息暂时无法读取（${prepared.missingMessageIds.length} 条）`);
        }
        const frame = frameRef.current;
        if (!frame) throw new WeComViewerError('frame-create', '企业微信会话组件不可用');
        await controllerRef.current.update(frame, { msgList }, generation,
          () => generationRef.current, controller.signal);
        if (isCurrent()) setStatus('mounted');
      } catch (error) {
        if (isAbortError(error) || !isCurrent()) return;
        fail(error);
      }
    };

    void run();
    return () => {
      disposed = true;
      controller.abort();
    };
  }, [contactPointId, target, items, reloadKey, retryKey, token, viewer]);

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
): WeComOpenDataFrameOptions {
  return {
    el: host,
    template: `
      <view wx:for="{{data.msgList}}" wx:key="msgid" class="wecom-row {{item.direction}}">
        <view class="wecom-bubble">
          <ww-open-message message-id="{{item.msgid}}" secret-key="{{item.secretKey}}" open-type="viewMessage" />
        </view>
      </view>
    `,
    style: `
      .wecom-row { box-sizing:border-box; display:flex; width:100%; min-height:38px; padding:4px 10px; }
      .wecom-row.inbound { justify-content:flex-start; }
      .wecom-row.outbound { justify-content:flex-end; }
      .wecom-bubble { box-sizing:border-box; display:inline-block; max-width:78%; min-height:30px; padding:6px 10px; overflow:hidden; border:1px solid ${token.colorBorderSecondary}; border-radius:${token.borderRadiusSM}px; background:${token.colorBgContainer}; }
      .wecom-row.outbound .wecom-bubble { border-color:#b7d4c6; background:#f4fbf7; }
    `,
    data: { msgList: [] },
    handleMounted,
    error: handleError,
    handleError,
  };
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}
