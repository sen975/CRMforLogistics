import { useEffect, useState } from 'react';
import { Alert, Button, Input, Modal, Space, Typography } from 'antd';

export default function TemplateRemarkModal({
  open,
  officialName,
  remark,
  saving,
  error,
  onCancel,
  onSave,
}: {
  open: boolean;
  officialName: string;
  remark: string | null;
  saving: boolean;
  error?: string | null;
  onCancel: () => void;
  onSave: (remark: string) => Promise<void> | void;
}) {
  const [value, setValue] = useState(remark ?? '');

  useEffect(() => {
    if (open) setValue(remark ?? '');
  }, [open, remark]);

  return (
    <Modal
      title="编辑模板备注"
      open={open}
      onCancel={onCancel}
      closable={!saving}
      maskClosable={!saving}
      keyboard={!saving}
      destroyOnHidden
      footer={(
        <Space>
          <Button disabled={saving} onClick={onCancel}>取消</Button>
          <Button type="primary" loading={saving} onClick={() => void onSave(value)}>保存备注</Button>
        </Space>
      )}
    >
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        {error && <Alert type="error" showIcon message={error} />}
        <div>
          <Typography.Text type="secondary">官方名称</Typography.Text>
          <Typography.Paragraph strong style={{ marginBottom: 0 }}>{officialName}</Typography.Paragraph>
        </div>
        <div>
          <Typography.Text type="secondary">本地备注</Typography.Text>
          <Input.TextArea
            aria-label="模板备注"
            value={value}
            maxLength={120}
            showCount
            autoSize={{ minRows: 3, maxRows: 5 }}
            placeholder="例如：仓库发货提醒"
            onChange={(event) => setValue(event.target.value)}
          />
        </div>
      </Space>
    </Modal>
  );
}
