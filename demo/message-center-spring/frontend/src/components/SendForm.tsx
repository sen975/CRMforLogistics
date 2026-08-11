import { useState, useMemo } from 'react';
import { Form, Input, Select, Button, Tabs, App, Upload } from 'antd';
import { SendOutlined, UploadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { sendEmail, sendChatApp, sendWeCom, sendChatAppMedia, fetchTemplates } from '../api/endpoints';
import type { ContactResponse } from '../api/types';
import CallRecordUploadForm from './CallRecordUploadForm';

const { TextArea } = Input;

interface SendFormProps {
  contact?: ContactResponse;
  onCallRecordCreated?: () => void;
}

function identityOptions(contact: ContactResponse, channelType: string) {
  return (contact.identities ?? [])
    .filter((i) => i.channelType === channelType)
    .map((i) => ({
      label: i.displayName ? `${i.displayName} (${i.identityValue})` : i.identityValue,
      value: i.identityValue,
    }));
}

function firstIdentityValue(contact: ContactResponse, channelType: string): string {
  const identity = contact.identities?.find((i) => i.channelType === channelType);
  return identity?.identityValue ?? '';
}

export default function SendForm({ contact, onCallRecordCreated }: SendFormProps) {
  const [sending, setSending] = useState(false);
  const [templateCode, setTemplateCode] = useState<string | undefined>(undefined);
  const [mediaMode, setMediaMode] = useState<string>('image');
  const [attachmentFiles, setAttachmentFiles] = useState<File[]>([]);
  const [mediaFile, setMediaFile] = useState<File | null>(null);
  const { message } = App.useApp();
  const { data: templates } = useQuery({
    queryKey: ['templates'],
    queryFn: fetchTemplates,
  });

  const selectedTemplate = useMemo(
    () => templates?.find((t) => t.templateCode === templateCode),
    [templates, templateCode],
  );

  const channels = contact?.channelTypes ?? [];
  const defaultChannel = channels[0] ?? 'email';

  const handleEmail = async (values: Record<string, string>) => {
    setSending(true);
    try {
      await sendEmail({
        to: values.to,
        subject: values.subject,
        body: values.body || '',
        attachments: attachmentFiles.length > 0 ? attachmentFiles : undefined,
      });
      message.success('邮件已发送');
      setAttachmentFiles([]);
    } catch {
      message.error('发送失败');
    } finally {
      setSending(false);
    }
  };

  const handleChatApp = async (values: Record<string, string>) => {
    setSending(true);
    try {
      await sendChatApp({
        mode: 'text',
        to: values.to,
        text: values.text,
        clientRequestId: crypto.randomUUID(),
      });
      message.success('消息已进入发送队列');
    } catch {
      message.error('发送失败');
    } finally {
      setSending(false);
    }
  };

  const handleTemplate = async (values: Record<string, string>) => {
    const tpl = templates?.find((t) => t.templateCode === values.templateCode);
    const params: Record<string, string> = {};
    if (tpl?.placeholders) {
      for (const key of tpl.placeholders) {
        if (values[key]) {
          params[key] = values[key];
        }
      }
    }
    setSending(true);
    try {
      await sendChatApp({
        mode: 'template',
        to: values.to,
        templateCode: values.templateCode,
        templateName: tpl?.templateName,
        languageCode: tpl?.languageCode,
        templateParamsJson: JSON.stringify(params),
        clientRequestId: crypto.randomUUID(),
      });
      message.success('模板消息已进入发送队列');
    } catch {
      message.error('发送失败');
    } finally {
      setSending(false);
    }
  };

  const handleMedia = async (values: Record<string, string>) => {
    if (!mediaFile) {
      message.error('请选择文件');
      return;
    }
    setSending(true);
    try {
      await sendChatAppMedia({
        to: values.to,
        mediaType: mediaMode,
        caption: values.caption,
        file: mediaFile,
      });
      message.success('媒体消息已进入发送队列');
      setMediaFile(null);
    } catch {
      message.error('发送失败');
    } finally {
      setSending(false);
    }
  };

  const handleWeCom = async (values: Record<string, string>) => {
    setSending(true);
    try {
      await sendWeCom({
        corpId: values.corpId,
        agentId: values.agentId,
        to: values.to,
        text: values.text,
      });
      message.success('企业微信消息已发送');
    } catch {
      message.error('发送失败');
    } finally {
      setSending(false);
    }
  };

  if (!contact) {
    return <div style={{ padding: 16, color: '#999' }}>选择一个联系人开始发送消息</div>;
  }

  const toSelect = (channel: string) => {
    const options = identityOptions(contact, channel);
    if (options.length === 0) {
      return <Input placeholder={channel === 'email' ? '收件人邮箱' : '手机号'} />;
    }
    return <Select placeholder={channel === 'email' ? '选择收件人' : '选择收件人'} options={options} />;
  };

  const channelTabs = [
    ...(channels.includes('email')
      ? [{
          key: 'email',
          label: '邮件',
          children: (
            <Form
              onFinish={handleEmail}
              layout="vertical"
              size="small"
              initialValues={{ to: firstIdentityValue(contact, 'email') }}
            >
              <Form.Item name="to" label="收件人" rules={[{ required: true }]}>
                {toSelect('email')}
              </Form.Item>
              <Form.Item name="subject" label="主题" rules={[{ required: true }]}>
                <Input placeholder="邮件主题" />
              </Form.Item>
              <Form.Item name="body" label="正文">
                <TextArea rows={6} placeholder="邮件正文" />
              </Form.Item>
              <Form.Item label="附件">
                <Upload
                  multiple
                  fileList={attachmentFiles.map((f, i) => ({
                    uid: `${i}-${f.name}`,
                    name: f.name,
                    status: 'done' as const,
                  }))}
                  beforeUpload={(file) => {
                    setAttachmentFiles((prev) => [...prev, file]);
                    return false;
                  }}
                  onRemove={(f) => {
                    setAttachmentFiles((prev) => prev.filter((_, i) => `${i}-${prev[i].name}` !== f.uid));
                  }}
                >
                  <Button icon={<UploadOutlined />}>选择文件</Button>
                </Upload>
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={sending} icon={<SendOutlined />}>
                发送邮件
              </Button>
            </Form>
          ),
        }]
      : []),
    ...(channels.includes('chatapp')
      ? [{
          key: 'chatapp',
          label: 'ChatApp',
          children: (
            <Tabs
              size="small"
              items={[
                {
                  key: 'text',
                  label: '文本',
                  children: (
                    <Form
                      onFinish={handleChatApp}
                      layout="vertical"
                      size="small"
                      initialValues={{ to: firstIdentityValue(contact, 'chatapp') }}
                    >
                      <Form.Item name="to" label="收件人" rules={[{ required: true }]}>
                        {toSelect('chatapp')}
                      </Form.Item>
                      <Form.Item name="text" label="消息" rules={[{ required: true }]}>
                        <TextArea rows={4} placeholder="消息内容" />
                      </Form.Item>
                      <Button type="primary" htmlType="submit" loading={sending} icon={<SendOutlined />}>
                        发送
                      </Button>
                    </Form>
                  ),
                },
                {
                  key: 'template',
                  label: '模板',
                  children: (
                    <Form
                      onFinish={handleTemplate}
                      layout="vertical"
                      size="small"
                      initialValues={{ to: firstIdentityValue(contact, 'chatapp') }}
                      onValuesChange={(changed) => {
                        if (changed.templateCode) setTemplateCode(changed.templateCode);
                      }}
                    >
                      <Form.Item name="to" label="收件人" rules={[{ required: true }]}>
                        {toSelect('chatapp')}
                      </Form.Item>
                      <Form.Item name="templateCode" label="模板" rules={[{ required: true }]}>
                        <Select
                          placeholder="选择模板"
                          options={templates?.map((t) => ({
                            label: `${t.templateName} (${t.languageCode})`,
                            value: t.templateCode,
                          }))}
                        />
                      </Form.Item>
                      {selectedTemplate?.placeholders?.map((key) => (
                        <Form.Item
                          key={key}
                          name={key}
                          label={key}
                          rules={[{ required: true, message: `请输入 ${key}` }]}
                        >
                          <Input placeholder={`模板参数: ${key}`} />
                        </Form.Item>
                      ))}
                      <Button type="primary" htmlType="submit" loading={sending} icon={<SendOutlined />}>
                        发送模板
                      </Button>
                    </Form>
                  ),
                },
                {
                  key: 'image',
                  label: '图片',
                  children: (
                    <Form
                      onFinish={handleMedia}
                      layout="vertical"
                      size="small"
                      initialValues={{ to: firstIdentityValue(contact, 'chatapp') }}
                    >
                      <Form.Item name="to" label="收件人" rules={[{ required: true }]}>
                        {toSelect('chatapp')}
                      </Form.Item>
                      <Form.Item label="图片">
                        <Upload
                          accept="image/*"
                          fileList={mediaFile ? [{ uid: 'media', name: mediaFile.name, status: 'done' as const }] : []}
                          beforeUpload={(file) => { setMediaFile(file); setMediaMode('image'); return false; }}
                          onRemove={() => setMediaFile(null)}
                        >
                          <Button icon={<UploadOutlined />}>选择图片</Button>
                        </Upload>
                      </Form.Item>
                      <Form.Item name="caption" label="说明">
                        <Input placeholder="图片说明（可选）" />
                      </Form.Item>
                      <Button type="primary" htmlType="submit" loading={sending} icon={<SendOutlined />}>
                        发送图片
                      </Button>
                    </Form>
                  ),
                },
                {
                  key: 'video',
                  label: '视频',
                  children: (
                    <Form
                      onFinish={handleMedia}
                      layout="vertical"
                      size="small"
                      initialValues={{ to: firstIdentityValue(contact, 'chatapp') }}
                    >
                      <Form.Item name="to" label="收件人" rules={[{ required: true }]}>
                        {toSelect('chatapp')}
                      </Form.Item>
                      <Form.Item label="视频">
                        <Upload
                          accept="video/*"
                          fileList={mediaFile ? [{ uid: 'media', name: mediaFile.name, status: 'done' as const }] : []}
                          beforeUpload={(file) => { setMediaFile(file); setMediaMode('video'); return false; }}
                          onRemove={() => setMediaFile(null)}
                        >
                          <Button icon={<UploadOutlined />}>选择视频</Button>
                        </Upload>
                      </Form.Item>
                      <Form.Item name="caption" label="说明">
                        <Input placeholder="视频说明（可选）" />
                      </Form.Item>
                      <Button type="primary" htmlType="submit" loading={sending} icon={<SendOutlined />}>
                        发送视频
                      </Button>
                    </Form>
                  ),
                },
                {
                  key: 'document',
                  label: '文件',
                  children: (
                    <Form
                      onFinish={handleMedia}
                      layout="vertical"
                      size="small"
                      initialValues={{ to: firstIdentityValue(contact, 'chatapp') }}
                    >
                      <Form.Item name="to" label="收件人" rules={[{ required: true }]}>
                        {toSelect('chatapp')}
                      </Form.Item>
                      <Form.Item label="文件">
                        <Upload
                          fileList={mediaFile ? [{ uid: 'media', name: mediaFile.name, status: 'done' as const }] : []}
                          beforeUpload={(file) => { setMediaFile(file); setMediaMode('document'); return false; }}
                          onRemove={() => setMediaFile(null)}
                        >
                          <Button icon={<UploadOutlined />}>选择文件</Button>
                        </Upload>
                      </Form.Item>
                      <Form.Item name="caption" label="说明">
                        <Input placeholder="文件说明（可选）" />
                      </Form.Item>
                      <Button type="primary" htmlType="submit" loading={sending} icon={<SendOutlined />}>
                        发送文件
                      </Button>
                    </Form>
                  ),
                },
              ]}
            />
          ),
        }]
      : []),
    ...(channels.includes('wecom')
      ? [{
          key: 'wecom',
          label: '企业微信',
          children: (
            <Form
              onFinish={handleWeCom}
              layout="vertical"
              size="small"
              initialValues={{ to: firstIdentityValue(contact, 'wecom') }}
            >
              <Form.Item name="corpId" label="企业ID" rules={[{ required: true }]}>
                <Input placeholder="企业微信CorpId" />
              </Form.Item>
              <Form.Item name="agentId" label="应用AgentId" rules={[{ required: true }]}>
                <Input placeholder="应用AgentId" />
              </Form.Item>
              <Form.Item name="to" label="接收人" rules={[{ required: true }]}>
                {toSelect('wecom')}
              </Form.Item>
              <Form.Item name="text" label="消息内容" rules={[{ required: true }]}>
                <TextArea rows={4} placeholder="消息文本内容" />
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={sending} icon={<SendOutlined />}>
                发送企业微信
              </Button>
            </Form>
          ),
        }]
      : []),
    {
      key: 'call',
      label: '电话记录',
      children: <CallRecordUploadForm contact={contact} onSuccess={onCallRecordCreated} />,
    },
  ];

  return (
    <div style={{ padding: 16 }}>
      <Tabs defaultActiveKey={defaultChannel} items={channelTabs} />
    </div>
  );
}
