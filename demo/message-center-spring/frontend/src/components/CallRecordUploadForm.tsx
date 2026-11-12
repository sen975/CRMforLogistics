import { useState } from 'react';
import { Form, Select, Radio, DatePicker, Input, Upload, Button, App, InputNumber } from 'antd';
import { AudioOutlined, PhoneOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { createCallRecord, bindPhoneContact, fetchContacts } from '../api/endpoints';
import type { ContactResponse } from '../api/types';
import dayjs from 'dayjs';

const { TextArea } = Input;

interface CallRecordUploadFormProps {
  contact?: ContactResponse;
  onSuccess?: () => void;
}

export default function CallRecordUploadForm({ contact, onSuccess }: CallRecordUploadFormProps) {
  const [sending, setSending] = useState(false);
  const [mp3File, setMp3File] = useState<File | null>(null);
  const [direction, setDirection] = useState<'inbound' | 'outbound'>('inbound');
  const { message } = App.useApp();

  const { data: contactsPage } = useQuery({
    queryKey: ['contacts', ''],
    queryFn: () => fetchContacts({ size: 200 }),
  });

  const contacts = contactsPage?.records ?? [];
  const phoneIdentities = contact
    ? (contact.identities ?? []).filter((i) => i.channelType === 'phone')
    : [];

  const handleSubmit = async (values: Record<string, unknown>) => {
    if (!mp3File) {
      message.error('请选择MP3录音文件');
      return;
    }
    setSending(true);
    try {
      const formData = new FormData();
      const targetContactId = values.contactId as string || contact?.id || '';
      let phonePointId = values.phonePointId as string || '';

      if (values.newPhone && targetContactId) {
        const bindResult = await bindPhoneContact({
          contactId: targetContactId,
          contactName: contact?.displayName,
          phoneNumber: values.newPhone as string,
        });
        phonePointId = bindResult.phonePointId;
      } else if (values.newPhone) {
        phonePointId = `phone:${values.newPhone as string}`;
      }

      formData.append('phonePointId', phonePointId);
      formData.append('direction', direction);
      formData.append('occurredAt', (values.occurredAt as dayjs.Dayjs).toISOString());
      formData.append('clientRequestId', crypto.randomUUID());
      if (values.note) formData.append('note', values.note as string);
      formData.append('file', mp3File);

      await createCallRecord(targetContactId || undefined, formData);
      message.success('录音上传成功，已加入转录队列');
      setMp3File(null);
      onSuccess?.();
    } catch {
      message.error('上传失败');
    } finally {
      setSending(false);
    }
  };

  return (
    <Form
      onFinish={handleSubmit}
      className="send-form"
      layout="vertical"
      size="small"
      initialValues={{
        direction: 'inbound',
        occurredAt: dayjs(),
        contactId: contact?.id,
        phonePointId: phoneIdentities.length > 0
          ? `phone:${phoneIdentities[0].identityValue.replace(/[^0-9]/g, '')}`
          : undefined,
      }}
    >
      {!contact && (
        <Form.Item name="contactId" label="联系人" className="send-meta-inline">
          <Select
            showSearch
            placeholder="选择联系人"
            filterOption={(input, option) =>
              (option?.label as string)?.toLowerCase().includes(input.toLowerCase())
            }
            options={contacts.map((c) => ({
              label: c.displayName,
              value: c.id,
            }))}
          />
        </Form.Item>
      )}

      <div className="send-meta-row send-meta-row-3">
      {phoneIdentities.length > 0 ? (
        <Form.Item name="phonePointId" label="电话号码" rules={[{ required: true, message: '请选择电话号码' }]}>
          <Select
            placeholder="选择电话号码"
            options={phoneIdentities.map((i) => ({
              label: i.displayName ? `${i.displayName} (${i.identityValue})` : i.identityValue,
              value: `phone:${i.identityValue.replace(/[^0-9]/g, '')}`,
            }))}
          />
        </Form.Item>
      ) : (
        <Form.Item name="newPhone" label="电话号码" rules={[{ required: true, message: '请输入电话号码' }]}>
          <Input placeholder="例如: +8613800000000" prefix={<PhoneOutlined />} />
        </Form.Item>
      )}

      <Form.Item name="direction" label="通话方向">
        <Radio.Group onChange={(e) => setDirection(e.target.value)}>
          <Radio.Button value="inbound">呼入</Radio.Button>
          <Radio.Button value="outbound">呼出</Radio.Button>
        </Radio.Group>
      </Form.Item>

      <Form.Item name="occurredAt" label="通话时间" rules={[{ required: true, message: '请选择通话时间' }]}>
        <DatePicker showTime style={{ width: '100%' }} />
      </Form.Item>
      </div>

      <Form.Item name="note" label="备注" className="send-grow">
        <TextArea rows={3} placeholder="备注（可选）" />
      </Form.Item>

      {/* 录音文件就地显示在工具栏左侧：另起一行会把备注区挤掉一截，
          发送区高度是定死的。没选文件时这一格让给格式提示。 */}
      <div className="send-toolbar">
        <div className="send-tools">
          <Upload
            accept="audio/mpeg,.mp3"
            showUploadList={false}
            fileList={mp3File ? [{ uid: 'mp3', name: mp3File.name, status: 'done' as const }] : []}
            beforeUpload={(file) => {
              if (!file.type.startsWith('audio/') && !file.name.toLowerCase().endsWith('.mp3')) {
                message.error('仅支持MP3格式');
                return false;
              }
              if (file.size > 100 * 1024 * 1024) {
                message.error('文件不能超过100MB');
                return false;
              }
              setMp3File(file);
              return false;
            }}
            onRemove={() => setMp3File(null)}
            maxCount={1}
          >
            <Button
              className="send-tool"
              icon={<AudioOutlined />}
              aria-label="选择MP3录音文件"
              title="选择MP3录音文件"
            />
          </Upload>
          {mp3File ? (
            <span className="send-attachment">
              <AudioOutlined />
              <span className="send-attachment-name">{mp3File.name}</span>
              <button
                type="button"
                className="send-attachment-remove"
                aria-label={`移除 ${mp3File.name}`}
                onClick={() => setMp3File(null)}
              >
                ×
              </button>
            </span>
          ) : (
            <span className="send-tool-hint">支持 MP3，单文件 ≤ 100MB</span>
          )}
        </div>
        <Button className="send-submit" type="primary" htmlType="submit" loading={sending}>
          上传录音
        </Button>
      </div>
    </Form>
  );
}
