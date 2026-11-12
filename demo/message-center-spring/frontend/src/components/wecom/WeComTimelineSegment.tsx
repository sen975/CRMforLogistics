import { ReloadOutlined, WarningOutlined, WechatOutlined } from '@ant-design/icons';
import { Button, Flex, Modal, Spin, Typography, theme } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { MessageResponse } from '../../api/types';
import type { WeComViewerHandle } from '../../hooks/useWeComViewer';
import {
  weComFrameRegistry,
} from '../../wecom/WeComFrameRegistry';
import type { WeComTimelineBlock, WeComTimelineMode } from '../../wecom/segmentWeComTimeline';
import type { WeComOpenDataFrame } from '../../wecom/wecomSdk';
import { asWeComViewerError, formatWeComViewerError, WeComViewerError } from '../../wecom/wecomErrors';

const { Text } = Typography;
const FRAME_MOUNT_TIMEOUT_MS = 15_000;

interface WeComTimelineSegmentProps {
  contactPointId: string;
  mode: WeComTimelineMode;
  segment: WeComTimelineBlock<MessageResponse>;
  viewer: WeComViewerHandle;
}

interface PreviewState {
  url: string;
  width: number;
  height: number;
}

function previewState(
  modalUrl?: string,
  modalSize?: { width?: number; height?: number },
): PreviewState | null {
  if (!modalUrl) return null;
  try {
    const url = new URL(modalUrl, window.location.href);
    if (url.protocol !== 'https:') return null;
    return {
      url: url.toString(),
      width: Math.min(960, Math.max(360, Number(modalSize?.width) || 720)),
      height: Math.min(720, Math.max(160, Number(modalSize?.height) || 420)),
    };
  } catch {
    return null;
  }
}

export function WeComTimelineSegment({
  contactPointId,
  mode,
  segment,
  viewer,
}: WeComTimelineSegmentProps) {
  const { token } = theme.useToken();
  const hostRef = useRef<HTMLDivElement>(null);
  const frameMountRef = useRef<HTMLDivElement>(null);
  const detailFrameRef = useRef<HTMLIFrameElement>(null);
  const generationRef = useRef(0);
  const [status, setStatus] = useState<'loading' | 'mounted' | 'failed'>('loading');
  const [failureMessage, setFailureMessage] = useState<string | null>(null);
  const [retryKey, setRetryKey] = useState(0);
  const [preview, setPreview] = useState<PreviewState | null>(null);
  const registryKey = `${contactPointId}:${segment.id}`;
  const messageIds = segment.items.map((item) => item.sourceId || '');

  useEffect(() => {
    const detailFrame = detailFrameRef.current;
    if (!detailFrame || !preview) return;
    weComFrameRegistry.registerDetailFrame(registryKey, detailFrame);
  }, [preview, registryKey]);

  useEffect(() => {
    const generation = ++generationRef.current;
    const controller = new AbortController();
    const isCurrent = () => !controller.signal.aborted && generationRef.current === generation;
    let mounted = false;
    let componentErrorReported = false;
    setStatus('loading');
    setFailureMessage(null);
    setPreview(null);
    let mountTimer: number | undefined;

    void weComFrameRegistry.enqueue(registryKey, contactPointId, async () => {
      const prepared = await viewer.prepareSegment(contactPointId, messageIds, controller.signal);
      if (!isCurrent() || !frameMountRef.current) throw abortError();
      const factory = prepared.sdk.createOpenDataFrameFactory?.();
      if (!factory) throw new WeComViewerError('frame-create', '企业微信会话组件不可用');
      let frame: WeComOpenDataFrame | null = null;
      const clearMountTimer = () => {
        if (mountTimer !== undefined) {
          window.clearTimeout(mountTimer);
          mountTimer = undefined;
        }
      };
      const fail = (error: unknown, stage: 'frame-create' | 'frame-mount' = 'frame-create') => {
        clearMountTimer();
        const viewerError = error instanceof WeComViewerError ? error : asWeComViewerError(error, stage);
        console.warn('[wecom-viewer/component-error]', {
          segmentId: segment.id,
          messageCount: segment.items.length,
          stage: viewerError.stage,
          errorType: viewerError.name,
          status: viewerError.status,
          code: viewerError.code,
        });
        if (isCurrent() && !componentErrorReported) {
          componentErrorReported = true;
          if (prepared.viewerSessionId) {
            void viewer.reportComponentError(prepared.viewerSessionId, prepared.viewerAuthToken);
          }
        }
        if (!mounted && isCurrent()) {
          frame?.dispose();
          setFailureMessage(formatWeComViewerError(viewerError));
          setStatus('failed');
        }
      };
      frame = factory.createOpenDataFrame({
        el: frameMountRef.current,
        template: `
          <view wx:for="{{data.msgList}}" wx:key="msgid" class="wecom-row {{item.direction}}">
            <view class="wecom-bubble">
              <ww-open-message message-id="{{item.msgid}}" secret-key="{{item.secretKey}}"
                open-type="viewMessage" binderror="handleMessageError" />
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
        data: {
          msgList: prepared.messages.map((message) => ({
            ...message,
            direction: segment.items.find((item) => item.sourceId === message.msgid)?.direction === 'outbound'
              ? 'outbound'
              : 'inbound',
          })),
        },
        methods: { handleMessageError: fail },
        handleMounted() {
          if (!isCurrent()) {
            frame?.dispose();
            return;
          }
          frame?.el.style.setProperty('display', 'block');
          frame?.el.style.setProperty('width', '100%');
          frame?.el.style.setProperty('height', '100%');
          frame?.el.style.setProperty('min-width', '0');
          frame?.el.style.setProperty('min-height', '0');
          clearMountTimer();
          mounted = true;
          weComFrameRegistry.touch(registryKey);
          setStatus('mounted');
        },
        handleModal({ modalUrl, modalSize }) {
          if (!isCurrent()) return false;
          const next = previewState(modalUrl, modalSize);
          if (!next) {
            fail(new Error('企业微信会话详情链接无效'));
            return false;
          }
          setPreview(next);
          return false;
        },
        error: fail,
      });
      mountTimer = window.setTimeout(
        () => fail(new WeComViewerError('frame-mount', '企业微信组件挂载超时'), 'frame-mount'),
        FRAME_MOUNT_TIMEOUT_MS,
      );
      return frame;
    }).catch((error) => {
      if (!isCurrent() || isAbortError(error)) return;
      const viewerError = error instanceof WeComViewerError ? error : asWeComViewerError(error, 'frame-create');
      console.warn('[wecom-viewer/frame-create-error]', {
        segmentId: segment.id,
        messageCount: segment.items.length,
        stage: viewerError.stage,
        errorType: viewerError.name,
        status: viewerError.status,
        code: viewerError.code,
      });
      if (!mounted) {
        setFailureMessage(formatWeComViewerError(viewerError));
        setStatus('failed');
      }
    });

    return () => {
      controller.abort();
      if (mountTimer !== undefined) window.clearTimeout(mountTimer);
      weComFrameRegistry.release(registryKey);
    };
  }, [contactPointId, registryKey, retryKey, segment.id, segment.items, viewer]);

  const frameHeight = mode === 'standalone'
    ? '100%'
    : `${Math.max(48, segment.items.length * 48 + 8)}px`;
  const standalone = mode === 'standalone';

  return (
    <div
      style={{
        width: '100%',
        height: standalone ? '100%' : undefined,
        flex: standalone ? '1 1 auto' : undefined,
        minHeight: 0,
        minWidth: 0,
        marginBottom: standalone ? 0 : 12,
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      <div
        style={{
          position: 'relative',
          width: '100%',
          height: standalone ? '100%' : frameHeight,
          flex: standalone ? '1 1 auto' : undefined,
          minHeight: 0,
          minWidth: 0,
        }}
      >
        <div
          ref={hostRef}
          data-testid={status === 'mounted' ? 'wecom-frame-visible' : 'wecom-frame-host'}
          aria-hidden={status !== 'mounted'}
          style={{
            position: 'relative',
            width: '100%',
            height: '100%',
            minWidth: 0,
            minHeight: 0,
            overflow: 'hidden',
            display: 'block',
            visibility: status === 'mounted' ? 'visible' : 'hidden',
            pointerEvents: status === 'mounted' ? 'auto' : 'none',
          }}
        >
          <div
            ref={frameMountRef}
            aria-hidden="true"
            style={{
              position: 'absolute',
              inset: 0,
              width: '100%',
              height: '100%',
              minWidth: 0,
              minHeight: 0,
            }}
          />
        </div>
      {status === 'loading' && (
        <Flex
          aria-label="企业微信消息加载中"
          align="center"
          justify="center"
          gap={8}
          style={{ position: 'absolute', inset: 0, minHeight: standalone ? 0 : 48 }}
        >
          <Spin size="small" />
          <Text type="secondary" style={{ fontSize: 12 }}>企业微信消息加载中</Text>
        </Flex>
      )}
      {status === 'failed' && (
        <Flex
          aria-label="企业微信消息加载失败"
          align="center"
          gap={6}
          style={{ minHeight: 32, color: token.colorTextSecondary }}
        >
          <WarningOutlined style={{ color: token.colorWarning }} />
          <Text type="secondary" style={{ fontSize: 12 }}>
            企业微信消息加载失败{failureMessage ? `：${failureMessage}` : ''}
          </Text>
          <Button
            type="text"
            size="small"
            icon={<ReloadOutlined />}
            aria-label="重新加载企业微信消息"
            onClick={() => setRetryKey((value) => value + 1)}
          />
        </Flex>
      )}
      </div>
      {mode === 'mixed' && status === 'mounted' && (
        <Flex align="center" gap={6} style={{ padding: '3px 8px 0' }}>
          <WechatOutlined style={{ color: token.colorTextSecondary }} />
          <Text type="secondary" style={{ fontSize: 11 }}>
            企业微信{segment.items.length > 1 ? ` · ${segment.items.length} 条连续消息` : ''}
          </Text>
        </Flex>
      )}
      <Modal
        title="企业微信会话详情"
        open={!!preview}
        footer={null}
        width={preview?.width}
        onCancel={() => setPreview(null)}
        destroyOnHidden
      >
        {preview && (
          <iframe
            ref={detailFrameRef}
            title="企业微信会话详情"
            src={preview.url}
            style={{ display: 'block', width: '100%', height: preview.height, border: 0 }}
            sandbox="allow-scripts allow-same-origin allow-forms allow-popups"
            referrerPolicy="no-referrer-when-downgrade"
          />
        )}
      </Modal>
    </div>
  );
}

function abortError(): DOMException {
  return new DOMException('企业微信消息渲染已取消', 'AbortError');
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}
