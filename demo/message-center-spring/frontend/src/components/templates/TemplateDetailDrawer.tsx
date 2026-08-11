import { Alert, Descriptions, Drawer, Empty, List, Space, Tag, Typography } from 'antd';
import type { TemplateAdmin, TemplateOperation } from '../../api/types';
import { headerFormatLabels, statusColors, statusLabels } from './templateUi';

const { Text, Paragraph } = Typography;

export default function TemplateDetailDrawer({
  template,
  operations,
  loading,
  open,
  onClose,
}: {
  template: TemplateAdmin | null;
  operations: TemplateOperation[];
  loading?: boolean;
  open: boolean;
  onClose: () => void;
}) {
  return (
    <Drawer title="模板详情" open={open} onClose={onClose} width="min(520px, 100vw)" loading={loading}>
      {!template ? <Empty description="没有可显示的模板详情" /> : (
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="模板名称">{template.name}</Descriptions.Item>
            <Descriptions.Item label="模板代码">{template.templateCode}</Descriptions.Item>
            <Descriptions.Item label="语言">{template.language}</Descriptions.Item>
            <Descriptions.Item label="类别">{template.category ?? '未提供'}</Descriptions.Item>
            <Descriptions.Item label="审核状态">
              <Tag color={statusColors[template.reviewStatus]}>{statusLabels[template.reviewStatus]}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="原始审核状态">{template.providerAuditStatus ?? '未提供'}</Descriptions.Item>
            <Descriptions.Item label="发送状态">{template.allowSend ? '已启用' : '已暂停'}</Descriptions.Item>
            <Descriptions.Item label="质量">{template.qualityScore ?? '未提供'}</Descriptions.Item>
            <Descriptions.Item label="发送 TTL">{template.messageSendTtlSeconds ?? '未提供'}</Descriptions.Item>
          </Descriptions>
          {template.rejectionReason && <Alert type="error" message="拒绝原因" description={template.rejectionReason} />}
          <section>
            <Typography.Title level={5}>组件</Typography.Title>
            {template.components.map((component, index) => (
              <div key={`${component.type}-${index}`} style={{ marginBottom: 12 }}>
                <Text strong>{component.type}</Text>
                {component.headerFormat && <Tag style={{ marginLeft: 8 }}>{headerFormatLabels[component.headerFormat]}</Tag>}
                {component.text && <Paragraph style={{ whiteSpace: 'pre-wrap', margin: '4px 0 0' }}>{component.text}</Paragraph>}
                {component.mediaAssetId && <Text type="secondary">素材 ID：{component.mediaAssetId}</Text>}
                {component.buttons.length > 0 && <List size="small" dataSource={component.buttons} renderItem={(button) => <List.Item>{button.type}：{button.text ?? ''}</List.Item>} />}
              </div>
            ))}
          </section>
          <section>
            <Typography.Title level={5}>变量示例</Typography.Title>
            {Object.keys(template.examples).length === 0 ? <Text type="secondary">无变量示例</Text> : <List size="small" dataSource={Object.entries(template.examples)} renderItem={([name, values]) => <List.Item><Text code>{`{{${name}}}`}</Text><span>{values.join(' / ')}</span></List.Item>} />}
          </section>
          <section>
            <Typography.Title level={5}>操作记录</Typography.Title>
            {operations.length === 0 ? <Text type="secondary">暂无操作记录</Text> : <List size="small" dataSource={operations} renderItem={(operation) => <List.Item><Space direction="vertical" size={0}><Text>{operation.operationType}</Text><Text type="secondary">{operation.operationStatus}{operation.errorMessage ? `：${operation.errorMessage}` : ''}</Text></Space></List.Item>} />}
          </section>
        </Space>
      )}
    </Drawer>
  );
}

