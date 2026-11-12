import { Button, Form, Input, Modal } from 'antd';
import type { ChannelAddressBookChannel } from '../api/types';

interface Props {
  channel: ChannelAddressBookChannel;
  open: boolean;
  loading?: boolean;
  onCancel: () => void;
  onSubmit: (values: { displayName: string; address: string }) => void;
}

const config = {
  chatapp: { label: 'WhatsApp', address: '号码', placeholder: '例如：+8613812345678' },
  email: { label: '邮件', address: '邮箱', placeholder: '例如：name@example.com' },
  phone: { label: '电话', address: '号码', placeholder: '例如：13812345678' },
} as const;

export default function ChannelAddressBookForm({ channel, open, loading, onCancel, onSubmit }: Props) {
  const [form] = Form.useForm<{ displayName: string; address: string }>();
  const item = config[channel];
  return (
    <Modal title={`新增${item.label}联系人`} open={open} footer={null} destroyOnHidden
      onCancel={() => { form.resetFields(); onCancel(); }}>
      <Form form={form} layout="vertical" onFinish={(values) => onSubmit({
        displayName: values.displayName.trim(), address: values.address.trim(),
      })}>
        <Form.Item label="显示名称" name="displayName" rules={[{ required: true, message: '请输入显示名称' }, { max: 100 }]}>
          <Input autoFocus placeholder="联系人名称" />
        </Form.Item>
        <Form.Item label={item.address} name="address" rules={[{ required: true, message: `请输入${item.address}` }, { max: 200 }]}>
          <Input placeholder={item.placeholder} />
        </Form.Item>
        <Form.Item style={{ marginBottom: 0 }}>
          <Button onClick={() => { form.resetFields(); onCancel(); }}>取消</Button>
          <Button aria-label="保存" type="primary" htmlType="submit" loading={loading} style={{ marginLeft: 8 }}>保存</Button>
        </Form.Item>
      </Form>
    </Modal>
  );
}
