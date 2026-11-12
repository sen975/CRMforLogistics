import { useEffect, useState } from 'react';
import { Button, Image, List, Space, Spin, Typography } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { downloadAttachment, fetchMediaBlob } from '../api/endpoints';
import type { MessageAttachmentResponse } from '../api/types';

const { Text } = Typography;

function sizeLabel(size: number): string {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function Preview({ attachment }: { attachment: MessageAttachmentResponse }) {
  const [url, setUrl] = useState<string>();
  const [error, setError] = useState(false);

  useEffect(() => {
    let active = true;
    let objectUrl: string | undefined;
    fetchMediaBlob(attachment.id).then((blob) => {
      if (!active) return;
      objectUrl = URL.createObjectURL(blob);
      setUrl(objectUrl);
    }).catch(() => active && setError(true));
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [attachment.id]);

  if (error) return <Text type="danger">预览失败</Text>;
  if (!url) return <Spin size="small" />;
  if (attachment.mediaKind === 'image') return <Image width={96} src={url} alt={attachment.fileName} />;
  if (attachment.mediaKind === 'audio') return <audio controls src={url} style={{ maxWidth: '100%' }} />;
  return <video controls src={url} style={{ maxWidth: '100%', maxHeight: 180 }} />;
}

export default function EmailAttachmentList({ attachments }: { attachments: MessageAttachmentResponse[] }) {
  const [downloadError, setDownloadError] = useState<string>();
  if (!attachments.length) return <Text type="secondary">无附件</Text>;
  return (
    <>
      <List
        size="small"
        dataSource={attachments}
        renderItem={(attachment) => (
        <List.Item
          actions={[
            <Button
              key="download"
              size="small"
              icon={<DownloadOutlined />}
              onClick={async () => {
                setDownloadError(undefined);
                try {
                  await downloadAttachment(attachment.id, attachment.fileName);
                } catch {
                  setDownloadError(`下载失败：${attachment.fileName}`);
                }
              }}
            >
              下载
            </Button>,
          ]}
        >
          <Space direction="vertical" size={4}>
            <Space wrap>
              <Text>{attachment.fileName}</Text>
              <Text type="secondary">{sizeLabel(attachment.sizeBytes)}</Text>
            </Space>
            {['image', 'audio', 'video'].includes(attachment.mediaKind)
              ? <Preview attachment={attachment} />
              : null}
          </Space>
        </List.Item>
        )}
      />
      {downloadError ? <Text type="danger">{downloadError}</Text> : null}
    </>
  );
}
