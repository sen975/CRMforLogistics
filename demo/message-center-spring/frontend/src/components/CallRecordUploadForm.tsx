import { useState } from 'react';
import { Form, Select, Radio, DatePicker, Input, Upload, Button, App, InputNumber } from 'antd';
import { UploadOutlined, PhoneOutlined } from '@ant-design/icons';
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

      if (values.newPhone) {
        const bindResult = await bindPhoneContact({
          contactId: targetContactId,
          contactName: contact?.displayName,
          phoneNumber: values.newPhone as string,
        });
        phonePointId = bindResult.phonePointId;
      }

      formData.append('phonePointId', phonePointId);
      formData.append('direction', direction);
      formData.append('occurredAt', (values.occurredAt as dayjs.Dayjs).toISOString());
      formData.append('clientRequestId', crypto.randomUUID());
      if (values.note) formData.append('note', values.note as string);
      formData.append('file', mp3File);

      await createCallRecord(targetContactId, formData);
      message.success('录音上传成功，已加入转录队列');
      setMp3File(null);
      onSuccess?.();
    } catch {
      message.error('上传失败');
    } finally {
      setSending(false);
    }
  };

  if (!contact && contacts.length === 0) {
    return <div style={{ padding: 16, color: '#999' }}>暂无联系人数据</div>;
  }

  return (
    <Form
      onFinish={handleSubmit}
      layout="vertical"
      size="small"
      initialValues={{
        direction: 'inbound',
        occurredAt: dayjs(),
        contactId: contact?.id,
        phonePointId: phoneIdentities.length > 0 ? phoneIdentities[0].identityValue : undefined,
      }}
    >
      {!contact && (
        <Form.Item name="contactId" label="联系人" rules={[{ required: true, message: '请选择联系人' }]}>
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

      {phoneIdentities.length > 0 ? (
        <Form.Item name="phonePointId" label="电话号码" rules={[{ required: true, message: '请选择电话号码' }]}>
          <Select
            placeholder="选择电话号码"
            options={phoneIdentities.map((i) => ({
              label: i.displayName ? `${i.displayName} (${i.identityValue})` : i.identityValue,
              value: i.identityValue,
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

      <Form.Item name="note" label="备注">
        <TextArea rows={2} placeholder="备注（可选）" />
      </Form.Item>

      <Form.Item label="录音文件" required>
        <Upload
          accept="audio/mpeg,.mp3"
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
          <Button icon={<UploadOutlined />}>选择MP3录音文件</Button>
        </Upload>
      </Form.Item>

      <Button type="primary" htmlType="submit" loading={sending}>
        上传录音
      </Button>
    </Form>
  );
}
