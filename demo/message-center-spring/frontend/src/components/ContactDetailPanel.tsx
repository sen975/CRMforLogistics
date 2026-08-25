import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { Typography, Tag, Descriptions, Input, Button, Space, App, Spin, Divider, Popconfirm, List } from 'antd';
import { EditOutlined, CheckOutlined, CloseOutlined, ScissorOutlined } from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchContact, fetchMessage } from '../api/endpoints';
import { useUpdateContactRemark, useSplitContact } from '../hooks/useContacts';
import { useDetailPanel } from '../hooks/useDetailPanel';
import type { ContactIdentityResponse } from '../api/types';
import EmailAttachmentList from './EmailAttachmentList';

const { Text, Title } = Typography;

const channelLabels: Record<string, { label: string; color: string }> = {
  email: { label: '邮件', color: 'blue' },
  chatapp: { label: 'ChatApp', color: 'green' },
  wecom: { label: '企业微信', color: 'purple' },
  whatsapp: { label: 'WhatsApp', color: 'cyan' },
};

function formatFullTime(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN');
}

export default function ContactDetailPanel() {
  const { contactId } = useParams();
  const qc = useQueryClient();
  const { message: appMessage } = App.useApp();
  const [editingRemark, setEditingRemark] = useState(false);
  const [remarkValue, setRemarkValue] = useState('');
  const { selectedDetail, selectChannel } = useDetailPanel();
  const selectedMessageId = selectedDetail?.kind === 'message' ? selectedDetail.id : null;

  const { data: contact, isLoading } = useQuery({
    queryKey: ['contact', contactId],
    queryFn: () => fetchContact(contactId!),
    enabled: !!contactId,
  });

  const { data: messageDetail } = useQuery({
    queryKey: ['message', selectedMessageId],
    queryFn: () => fetchMessage(selectedMessageId!),
    enabled: !!selectedMessageId,
  });

  const updateRemark = useUpdateContactRemark();
  const splitMutation = useSplitContact();

  const handleSplit = async (identity: ContactIdentityResponse) => {
    try {
      await splitMutation.mutateAsync({
        identityId: identity.id,
        newContactName: identity.displayName || identity.identityValue,
      });
      qc.invalidateQueries({ queryKey: ['contact', contactId] });
      qc.invalidateQueries({ queryKey: ['contacts'] });
      appMessage.success('账号已拆分');
    } catch (e: any) {
      const errMsg = e?.response?.data?.message || e?.message || '拆分失败';
      appMessage.error(errMsg);
    }
  };

  if (!contactId) {
    return (
      <div style={{ padding: 16 }}>
        <Text type="secondary">选择联系人查看详情</Text>
      </div>
    );
  }

  if (isLoading || !contact) {
    return (
      <div style={{ textAlign: 'center', padding: 24 }}>
        <Spin />
      </div>
    );
  }

  const handleSaveRemark = async () => {
    try {
      await updateRemark.mutateAsync({ id: contact.id, remark: remarkValue });
      qc.invalidateQueries({ queryKey: ['contact', contactId] });
      appMessage.success('备注已更新');
      setEditingRemark(false);
    } catch {
      appMessage.error('更新失败');
    }
  };

  return (
    <div style={{ padding: 16 }}>
      <Title level={5} style={{ marginBottom: 16 }}>
        {contact.displayName || contact.remark || '未命名'}
      </Title>

      <Descriptions column={1} size="small" bordered>
        <Descriptions.Item label="渠道">
          <Space size={4} wrap>
            {contact.channelTypes?.map((ch) => {
              const info = channelLabels[ch] ?? { label: ch, color: 'default' };
              return (
                <Tag key={ch} color={info.color}>
                  {info.label}
                </Tag>
              );
            })}
          </Space>
        </Descriptions.Item>

        <Descriptions.Item label="备注">
          {editingRemark ? (
            <Space style={{ width: '100%' }}>
              <Input
                size="small"
                value={remarkValue}
                onChange={(e) => setRemarkValue(e.target.value)}
                style={{ flex: 1 }}
                autoFocus
              />
              <Button
                size="small"
                type="primary"
                icon={<CheckOutlined />}
                loading={updateRemark.isPending}
                onClick={handleSaveRemark}
              />
              <Button
                size="small"
                icon={<CloseOutlined />}
                onClick={() => setEditingRemark(false)}
              />
            </Space>
          ) : (
            <Space>
              <Text>{contact.remark || '-'}</Text>
              <Button
                type="link"
                size="small"
                icon={<EditOutlined />}
                onClick={() => {
                  setRemarkValue(contact.remark || '');
                  setEditingRemark(true);
                }}
              />
            </Space>
          )}
        </Descriptions.Item>

        <Descriptions.Item label="消息数">
          {contact.messageCount}
        </Descriptions.Item>

        <Descriptions.Item label="最后消息">
          {contact.lastMessageAt
            ? new Date(contact.lastMessageAt).toLocaleString('zh-CN')
            : '-'}
        </Descriptions.Item>

        <Descriptions.Item label="未读数">
          {contact.unreadCount}
        </Descriptions.Item>

        {contact.lastText && (
          <Descriptions.Item label="最近内容">
            <Text ellipsis style={{ maxWidth: 200 }}>
              {contact.lastText}
            </Text>
          </Descriptions.Item>
        )}
      </Descriptions>

      {contact.identities && contact.identities.length > 0 && (
        <>
          <Divider style={{ margin: '16px 0 12px' }} />
          <Title level={5} style={{ marginBottom: 8 }}>融合账号</Title>
          <List
            size="small"
            dataSource={contact.identities}
            renderItem={(identity) => (
              <List.Item
                onClick={() => selectChannel(identity.channelType)}
                style={{ cursor: 'pointer' }}
                actions={[
                  contact.identities.length > 1 ? (
                    <Popconfirm
                      key="split"
                      title="确定要拆分此账号吗？"
                      description="该账号将成为一个独立的联系人"
                      onConfirm={() => handleSplit(identity)}
                      okText="确定"
                      cancelText="取消"
                    >
                      <Button
                        size="small"
                        type="link"
                        danger
                        icon={<ScissorOutlined />}
                        loading={splitMutation.isPending}
                      >
                        拆分
                      </Button>
                    </Popconfirm>
                  ) : null,
                ].filter(Boolean)}
              >
                <List.Item.Meta
                  title={
                    <Space size={4}>
                      <Tag color={channelLabels[identity.channelType]?.color ?? 'default'}>
                        {channelLabels[identity.channelType]?.label ?? identity.channelType}
                      </Tag>
                      <Text>{identity.displayName || identity.identityValue}</Text>
                    </Space>
                  }
                  description={
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {identity.channelType === 'chatapp'
                        ? identity.identityValue
                        : `${identity.identityScope}: ${identity.identityValue}`}
                    </Text>
                  }
                />
              </List.Item>
            )}
          />
        </>
      )}

      {selectedMessageId && (
        <>
          <Divider style={{ margin: '16px 0 12px' }} />
          <Title level={5} style={{ marginBottom: 12 }}>消息详情</Title>

          {messageDetail ? (
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="方向">
                <Tag color={messageDetail.direction === 'inbound' ? 'blue' : 'green'}>
                  {messageDetail.direction === 'inbound' ? '接收' : '发送'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="类型">
                <Tag>{messageDetail.kind}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="渠道">
                <Tag>{messageDetail.channelType}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="发送方">
                {messageDetail.from}
              </Descriptions.Item>
              <Descriptions.Item label="接收方">
                {messageDetail.to}
              </Descriptions.Item>
              <Descriptions.Item label="时间">
                {formatFullTime(messageDetail.occurredAt)}
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag>{messageDetail.status}</Tag>
              </Descriptions.Item>
              {messageDetail.subject && (
                <Descriptions.Item label="主题">
                  <Text strong>{messageDetail.subject}</Text>
                </Descriptions.Item>
              )}
              <Descriptions.Item label="内容">
                <div style={{ maxHeight: 300, overflow: 'auto', whiteSpace: 'pre-wrap', fontSize: 13 }}>
                  {(messageDetail.kind === 'email' || messageDetail.channelType === 'email')
                    && messageDetail.bodyHtml ? (
                    <div dangerouslySetInnerHTML={{ __html: messageDetail.bodyHtml }} />
                  ) : (
                    <Text>{messageDetail.bodyText}</Text>
                  )}
                </div>
              </Descriptions.Item>
              <Descriptions.Item label="附件">
                <EmailAttachmentList attachments={messageDetail.attachments ?? []} />
              </Descriptions.Item>
            </Descriptions>
          ) : (
            <div style={{ textAlign: 'center', padding: 8 }}>
              <Spin size="small" />
            </div>
          )}
        </>
      )}
    </div>
  );
}
