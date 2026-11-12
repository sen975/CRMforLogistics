import { useCallback, useEffect, useRef, useState } from 'react';
import { Image, Space, Typography } from 'antd';
import { PictureOutlined } from '@ant-design/icons';
import { fetchMediaUrl } from '../api/endpoints';
import type { MessageAttachmentResponse } from '../api/types';

const { Text } = Typography;

interface MessageMediaProps {
  attachment: MessageAttachmentResponse;
}

export default function MessageMedia({ attachment }: MessageMediaProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const sourceRef = useRef<string | null>(null);
  const [visible, setVisible] = useState(() => typeof IntersectionObserver === 'undefined');
  const [source, setSource] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  const releaseSource = useCallback(() => {
    if (sourceRef.current && typeof URL.revokeObjectURL === 'function') {
      URL.revokeObjectURL(sourceRef.current);
    }
    sourceRef.current = null;
    setSource(null);
  }, []);

  useEffect(() => {
    const element = containerRef.current;
    if (!element || typeof IntersectionObserver === 'undefined') {
      setVisible(true);
      return;
    }
    const observer = new IntersectionObserver(
      ([entry]) => setVisible(entry?.isIntersecting === true),
      { rootMargin: '160px 0px' },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!visible) {
      releaseSource();
      setFailed(false);
      return;
    }

    let active = true;
    let blobUrl: string | null = null;
    releaseSource();
    setFailed(false);

    void fetchMediaUrl(attachment.id)
      .then((url) => {
        blobUrl = url;
        if (active) {
          sourceRef.current = url;
          setSource(url);
        } else if (typeof URL.revokeObjectURL === 'function') {
          URL.revokeObjectURL(url);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });

    return () => {
      active = false;
      if (blobUrl && sourceRef.current === blobUrl
          && typeof URL.revokeObjectURL === 'function') {
        URL.revokeObjectURL(blobUrl);
        sourceRef.current = null;
      }
    };
  }, [attachment.id, releaseSource, visible]);

  const handleDecodeError = () => {
    releaseSource();
    setFailed(true);
  };

  if (failed) {
    return (
      <Space size={6} style={{ maxWidth: 220 }}>
        <PictureOutlined />
        <Text type="secondary" style={{ overflowWrap: 'anywhere' }}>
          {attachment.fileName || '图片'}
        </Text>
      </Space>
    );
  }

  return (
    <div
      ref={containerRef}
      onClick={(event) => event.stopPropagation()}
      style={{ width: 220, height: 160, maxWidth: '100%' }}
    >
      {source ? (
        <Image
          src={source}
          alt={attachment.fileName || '图片'}
          width="100%"
          height={160}
          style={{ objectFit: 'cover', display: 'block' }}
          preview={{ mask: '预览' }}
          onError={handleDecodeError}
        />
      ) : (
        <div
          aria-label="图片加载中"
          style={{
            width: '100%',
            height: '100%',
            display: 'grid',
            placeItems: 'center',
            background: '#f5f5f5',
            color: '#8c8c8c',
          }}
        >
          <PictureOutlined style={{ fontSize: 24 }} />
        </div>
      )}
    </div>
  );
}
