import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Form, Input, Select, Button, Tabs, App, Upload, Spin } from 'antd';
import { SendOutlined, UploadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import {
  sendEmail,
  sendChatApp,
  sendWeCom,
  sendChatAppMedia,
  fetchChatAppSendableTemplates,
  fetchChannelCapabilities,
} from '../api/endpoints';
import type { ContactIdentityResponse, ContactResponse } from '../api/types';
import CallRecordUploadForm from './CallRecordUploadForm';

const { TextArea } = Input;
const EMAIL_ATTACHMENT_MAX_COUNT = 16;
const EMAIL_ATTACHMENT_MAX_TOTAL_BYTES = 20 * 1024 * 1024;

interface SendFormProps {
  contact?: ContactResponse;
  onCallRecordCreated?: () => void;
  selectedChannelAccountId?: string;
  selectedIdentityId?: string;
  activeChannel?: string;
  onChannelChange?: (channel: string) => void;
}

function identityOptions(contact: ContactResponse, channelType: string) {
  return (contact.identities ?? [])
    .filter((i) => i.channelType === channelType)
    .map((i) => ({
      label: i.displayName ? `${i.displayName} (${i.identityValue})` : i.identityValue,
      value: i.identityValue,
    }));
}

function firstIdentityValue(contact: ContactResponse | undefined, channelType: string): string {
  if (!contact) return '';
  const identity = contact.identities?.find((i) => i.channelType === channelType);
  return identity?.identityValue ?? '';
}

const TEMPLATE_PLACEHOLDER_PATTERN =
  /\{\{\s*([^{}]+?)\s*\}\}|\$\{\s*([^{}]+?)\s*\}|\$\(\s*([^()]+?)\s*\)/g;

function renderTemplateBody(body: string, values: Record<string, string>): string {
  return body.replace(
    TEMPLATE_PLACEHOLDER_PATTERN,
    (match, doubleBraceKey?: string, dollarBraceKey?: string, dollarParenKey?: string) => {
      const key = (doubleBraceKey ?? dollarBraceKey ?? dollarParenKey)?.trim();
      if (!key || !values[key]) {
        return match;
      }
      return values[key];
    },
  );
}

function identityLabel(identity: ContactIdentityResponse): string {
  return identity.displayName
    ? `${identity.displayName} (${identity.identityValue})`
    : identity.identityValue;
}

interface ChatAppRecipientFieldProps {
  identities: ContactIdentityResponse[];
  selectedIdentityId?: string;
  requiresExplicitSelection?: boolean;
  onChange: (identityId: string) => void;
}

function ChatAppRecipientField({
  identities,
  selectedIdentityId,
  requiresExplicitSelection = false,
  onChange,
}: ChatAppRecipientFieldProps) {
  if (identities.length === 0) {
    return (
      <Form.Item label="收件人" htmlFor="chatapp-recipient">
        <Input id="chatapp-recipient" value="无可用 ChatApp 账号" disabled />
      </Form.Item>
    );
  }
  if (identities.length === 1 && !requiresExplicitSelection) {
    return (
      <Form.Item label="收件人" htmlFor="chatapp-recipient" required>
        <Input
          id="chatapp-recipient"
          value={identityLabel(identities[0])}
          readOnly
        />
      </Form.Item>
    );
  }
  return (
    <Form.Item label="收件人" htmlFor="chatapp-recipient" required>
      <Select
        id="chatapp-recipient"
        value={selectedIdentityId}
        onChange={onChange}
        options={identities.map((identity) => ({
          label: identityLabel(identity),
          value: identity.id,
        }))}
      />
    </Form.Item>
  );
}

export default function SendForm({
  contact,
  onCallRecordCreated,
  selectedChannelAccountId,
  selectedIdentityId,
  activeChannel,
  onChannelChange,
}: SendFormProps) {
  const [sending, setSending] = useState(false);
  const [emailForm] = Form.useForm();
  const [selectedChatAppAccountId, setSelectedChatAppAccountId] = useState<string>();
  const [selectedChatAppIdentityId, setSelectedChatAppIdentityId] = useState<string>();
  const [chatAppRecipientConfirmationRequired, setChatAppRecipientConfirmationRequired] = useState(false);
  const previousFixedChannelAccountId = useRef(selectedChannelAccountId);
  const hasInitializedChatAppAccount = useRef(false);
  const [templateCode, setTemplateCode] = useState<string | undefined>(undefined);
  const [templateDraftValues, setTemplateDraftValues] = useState<Record<string, string>>({});
  const [templateForm] = Form.useForm();
  const previousTemplateAccountId = useRef<string | undefined>(undefined);
  const [mediaMode, setMediaMode] = useState<string>('image');
  const [attachmentFiles, setAttachmentFiles] = useState<File[]>([]);
  const [mediaFile, setMediaFile] = useState<File | null>(null);
  const { message } = App.useApp();
  const { data: channelCapabilities, isPending: channelCapabilitiesPending } = useQuery({
    queryKey: ['channelCapabilities'],
    queryFn: fetchChannelCapabilities,
  });

  const activeChatAppCapabilities = useMemo(
    () => (channelCapabilities ?? []).filter((channel) => (
      (channel.channelType === 'chatapp' || channel.channelType === 'whatsapp')
      && channel.authStatus === 'active'
    )),
    [channelCapabilities],
  );
  const activeContactChatAppAccounts = useMemo(
    () => activeChatAppCapabilities.filter((channel) => (
      (contact?.identities ?? []).some((identity) => (
        identity.channelType === 'chatapp'
        && identity.identityScope === channel.channelAccountId
      ))
    )),
    [activeChatAppCapabilities, contact?.identities],
  );
  const requestedIdentity = useMemo(
    () => (contact?.identities ?? []).find((identity) => identity.id === selectedIdentityId),
    [contact?.identities, selectedIdentityId],
  );
  useEffect(() => {
    if (channelCapabilitiesPending) {
      return;
    }
    if (previousFixedChannelAccountId.current !== selectedChannelAccountId) {
      previousFixedChannelAccountId.current = selectedChannelAccountId;
      setSelectedChatAppIdentityId(undefined);
      setChatAppRecipientConfirmationRequired(true);
    }

    if (selectedChannelAccountId) {
      if (selectedChatAppAccountId !== undefined) {
        setSelectedChatAppAccountId(undefined);
      }
      return;
    }

    if (activeContactChatAppAccounts.some(
      (channel) => channel.channelAccountId === selectedChatAppAccountId,
    )) {
      return;
    }

    const replacementChannelAccountId = activeContactChatAppAccounts[0]?.channelAccountId;
    if (selectedChatAppAccountId !== replacementChannelAccountId) {
      setSelectedChatAppAccountId(replacementChannelAccountId);
      setSelectedChatAppIdentityId(undefined);
      if (hasInitializedChatAppAccount.current) {
        setChatAppRecipientConfirmationRequired(true);
      } else {
        hasInitializedChatAppAccount.current = true;
      }
    }
  }, [
    activeContactChatAppAccounts,
    channelCapabilitiesPending,
    selectedChannelAccountId,
    selectedChatAppAccountId,
  ]);
  const effectiveChatAppAccountId = selectedChannelAccountId ?? selectedChatAppAccountId;
  useEffect(() => {
    if (previousTemplateAccountId.current === effectiveChatAppAccountId) {
      return;
    }
    previousTemplateAccountId.current = effectiveChatAppAccountId;
    setTemplateCode(undefined);
    setTemplateDraftValues({});
    templateForm.resetFields();
  }, [effectiveChatAppAccountId, templateForm]);
  const {
    data: templates,
    isError: templatesError,
    isPending: templatesPending,
    refetch: refetchTemplates,
  } = useQuery({
    queryKey: ['chatapp-sendable-templates', effectiveChatAppAccountId],
    queryFn: () => fetchChatAppSendableTemplates(effectiveChatAppAccountId!),
    enabled: Boolean(effectiveChatAppAccountId),
  });
  const selectedTemplate = useMemo(
    () => templates?.find((t) => t.templateCode === templateCode),
    [templates, templateCode],
  );
  const chatAppIdentities = useMemo(
    () => (contact?.identities ?? []).filter((identity) => (
      identity.channelType === 'chatapp'
      && identity.identityScope === effectiveChatAppAccountId
    )),
    [contact?.identities, effectiveChatAppAccountId],
  );
  useEffect(() => {
    if (channelCapabilitiesPending) {
      return;
    }
    if (selectedChatAppIdentityId && !chatAppIdentities.some(
      (identity) => identity.id === selectedChatAppIdentityId,
    )) {
      setSelectedChatAppIdentityId(undefined);
      setChatAppRecipientConfirmationRequired(true);
    }
  }, [channelCapabilitiesPending, chatAppIdentities, selectedChatAppIdentityId]);
  useEffect(() => {
    if (channelCapabilitiesPending || !requestedIdentity) return;
    if (requestedIdentity.channelType === 'email') {
      emailForm.setFieldValue('to', requestedIdentity.identityValue);
      return;
    }
    if (requestedIdentity.channelType === 'chatapp') {
      setSelectedChatAppAccountId(requestedIdentity.identityScope);
      setSelectedChatAppIdentityId(requestedIdentity.id);
      setChatAppRecipientConfirmationRequired(false);
    }
  }, [channelCapabilitiesPending, emailForm, requestedIdentity]);
  const effectiveChatAppIdentityId = chatAppIdentities.some(
    (identity) => identity.id === selectedChatAppIdentityId,
  )
    ? selectedChatAppIdentityId
    : !chatAppRecipientConfirmationRequired && chatAppIdentities.length === 1
      ? chatAppIdentities[0].id
      : undefined;

  const channels = contact?.channelTypes ?? [];
  const chatAppChannelAvailable = activeChatAppCapabilities.length > 0;
  const defaultChannel = channels[0] ?? 'email';
  const selectedEmailIdentityValue = requestedIdentity?.channelType === 'email'
    ? requestedIdentity.identityValue
    : firstIdentityValue(contact, 'email');

  const handleEmail = async (values: Record<string, string>, form?: any) => {
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
      form?.resetFields();
    } catch {
      message.error('发送失败');
    } finally {
      setSending(false);
    }
  };

  const handleChatApp = async (values: Record<string, string>) => {
    if (!contact || !effectiveChatAppIdentityId) {
      message.error('当前联系人没有可用的 ChatApp 账号');
      return;
    }
    setSending(true);
    try {
      await sendChatApp({
        mode: 'text',
        contactId: contact.id,
        recipientIdentityId: effectiveChatAppIdentityId,
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
    if (!contact || !effectiveChatAppIdentityId) {
      message.error('当前联系人没有可用的 ChatApp 账号');
      return;
    }
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
        contactId: contact.id,
        recipientIdentityId: effectiveChatAppIdentityId,
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
    if (!contact || !effectiveChatAppIdentityId) {
      message.error('当前联系人没有可用的 ChatApp 账号');
      return;
    }
    if (!mediaFile) {
      message.error('请选择文件');
      return;
    }
    setSending(true);
    try {
      await sendChatAppMedia({
        contactId: contact.id,
        recipientIdentityId: effectiveChatAppIdentityId,
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
  if (channelCapabilitiesPending) {
    return <div style={{ padding: 16 }}><Spin size="small" /></div>;
  }

  const toSelect = (channel: string) => {
    const options = identityOptions(contact, channel);
    if (options.length === 0) {
      return <Input placeholder={channel === 'email' ? '收件人邮箱' : '手机号'} />;
    }
    return <Select placeholder={channel === 'email' ? '选择收件人' : '选择收件人'} options={options} />;
  };

  const chatAppRecipientField = () => (
    <ChatAppRecipientField
      identities={chatAppIdentities}
      selectedIdentityId={effectiveChatAppIdentityId}
      requiresExplicitSelection={chatAppRecipientConfirmationRequired}
      onChange={(identityId) => {
        setSelectedChatAppIdentityId(identityId);
        setChatAppRecipientConfirmationRequired(false);
      }}
    />
  );

  const chatAppAccountField = !selectedChannelAccountId && activeContactChatAppAccounts.length > 1 ? (
    <Form.Item label="ChatApp 账号" htmlFor="chatapp-account">
      <Select
        id="chatapp-account"
        value={effectiveChatAppAccountId}
        onChange={(channelAccountId) => {
          setSelectedChatAppAccountId(channelAccountId);
          setSelectedChatAppIdentityId(undefined);
          setChatAppRecipientConfirmationRequired(false);
        }}
        options={activeContactChatAppAccounts.map((channel) => ({
          label: channel.displayName,
          value: channel.channelAccountId,
        }))}
      />
    </Form.Item>
  ) : null;

  const channelTabs = [
    ...(channels.includes('email')
      ? [{
          key: 'email',
          label: '邮件',
          children: (
            <Form
              form={emailForm}
              onFinish={(values) => handleEmail(values, emailForm)}
              layout="vertical"
              size="small"
              initialValues={{ to: selectedEmailIdentityValue }}
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
                    const nextCount = attachmentFiles.length + 1;
                    const nextSize = attachmentFiles.reduce((sum, item) => sum + item.size, 0) + file.size;
                    if (nextCount > EMAIL_ATTACHMENT_MAX_COUNT) {
                      message.error('最多添加 16 个附件');
                      return Upload.LIST_IGNORE;
                    }
                    if (nextSize > EMAIL_ATTACHMENT_MAX_TOTAL_BYTES) {
                      message.error('附件总大小不能超过 20 MiB');
                      return Upload.LIST_IGNORE;
                    }
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
    ...(chatAppChannelAvailable
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
                    >
                      {chatAppAccountField}
                      {chatAppRecipientField()}
                      <Form.Item name="text" label="消息" rules={[{ required: true }]}>
                        <TextArea rows={4} placeholder="消息内容" />
                      </Form.Item>
                      <Button type="primary" htmlType="submit" loading={sending}
                        disabled={!effectiveChatAppIdentityId} icon={<SendOutlined />}>
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
                      form={templateForm}
                      onFinish={handleTemplate}
                      layout="vertical"
                      size="small"
                      onValuesChange={(changed: Record<string, string | undefined>) => {
                        if (changed.templateCode !== undefined) {
                          setTemplateCode(changed.templateCode);
                          setTemplateDraftValues({});
                          const parameterFields = Object.keys(templateForm.getFieldsValue())
                            .filter((field) => field !== 'templateCode');
                          if (parameterFields.length > 0) {
                            templateForm.resetFields(parameterFields);
                          }
                          templateForm.setFieldsValue({ templateCode: changed.templateCode });
                          return;
                        }
                        setTemplateDraftValues((previous) => {
                          const next = { ...previous };
                          for (const [key, value] of Object.entries(changed)) {
                            next[key] = value ?? '';
                          }
                          return next;
                        });
                      }}
                    >
                      {chatAppAccountField}
                      {chatAppRecipientField()}
                      {templatesError && (
                        <Alert
                          type="error"
                          showIcon
                          message="模板加载失败"
                          description="请重试或检查当前 ChatApp 账号的模板同步状态"
                          action={<Button size="small" onClick={() => void refetchTemplates()}>重试</Button>}
                          style={{ marginBottom: 16 }}
                        />
                      )}
                      <Form.Item name="templateCode" label="模板" rules={[{ required: true }]}>
                        <Select
                          placeholder="选择模板"
                          loading={templatesPending}
                          disabled={templatesError}
                          options={templates?.map((t) => ({
                            label: `${t.displayName} (${t.languageCode})`,
                            value: t.templateCode,
                          }))}
                        />
                      </Form.Item>
                      {selectedTemplate && (
                        <div
                          aria-label="模板预览"
                          style={{
                            marginBottom: 16,
                            padding: '10px 12px',
                            border: '1px solid #d9d9d9',
                            borderRadius: 4,
                            background: '#fafafa',
                          }}
                        >
                          <div style={{ fontWeight: 600, marginBottom: 6 }}>
                            {selectedTemplate.displayName}
                          </div>
                          <div style={{ whiteSpace: 'pre-wrap' }}>
                            {renderTemplateBody(selectedTemplate.body, templateDraftValues)}
                          </div>
                        </div>
                      )}
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
                      <Button type="primary" htmlType="submit" loading={sending}
                        disabled={!effectiveChatAppIdentityId} icon={<SendOutlined />}>
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
                    >
                      {chatAppAccountField}
                      {chatAppRecipientField()}
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
                      <Button type="primary" htmlType="submit" loading={sending}
                        disabled={!effectiveChatAppIdentityId} icon={<SendOutlined />}>
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
                    >
                      {chatAppAccountField}
                      {chatAppRecipientField()}
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
                      <Button type="primary" htmlType="submit" loading={sending}
                        disabled={!effectiveChatAppIdentityId} icon={<SendOutlined />}>
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
                    >
                      {chatAppAccountField}
                      {chatAppRecipientField()}
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
                      <Button type="primary" htmlType="submit" loading={sending}
                        disabled={!effectiveChatAppIdentityId} icon={<SendOutlined />}>
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
  const channelKeys = channelTabs.map((tab) => tab.key);
  const effectiveActiveChannel = activeChannel && channelKeys.includes(activeChannel)
    ? activeChannel
    : channelKeys.includes(defaultChannel)
      ? defaultChannel
      : channelKeys[0];

  return (
    <div style={{ padding: 16 }}>
      <Tabs
        {...(activeChannel !== undefined
          ? { activeKey: effectiveActiveChannel }
          : { defaultActiveKey: effectiveActiveChannel })}
        onChange={onChannelChange}
        items={channelTabs}
      />
    </div>
  );
}
