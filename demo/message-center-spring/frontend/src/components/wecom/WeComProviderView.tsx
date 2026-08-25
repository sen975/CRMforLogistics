import { Descriptions, Empty, Table, Typography } from 'antd';
import type { WeComProviderData } from '../../api/types';

const { Text } = Typography;

function display(value: unknown): React.ReactNode {
  if (value === null || value === undefined || value === '') return <Text type="secondary">-</Text>;
  if (typeof value === 'object') return <Text code>{JSON.stringify(value)}</Text>;
  return String(value);
}

function firstRows(data: WeComProviderData): WeComProviderData[] {
  const value = Object.values(data).find((entry) => Array.isArray(entry));
  return Array.isArray(value)
    ? value.filter((entry): entry is WeComProviderData => !!entry && typeof entry === 'object' && !Array.isArray(entry))
    : [];
}

export default function WeComProviderView({ data, emptyText = '暂无数据' }: {
  data?: WeComProviderData;
  emptyText?: string;
}) {
  if (!data || Object.keys(data).length === 0) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyText} />;
  const rows = firstRows(data);
  if (rows.length > 0) {
    const keys = [...new Set(rows.flatMap((row) => Object.keys(row)))].slice(0, 8);
    return (
      <Table
        size="small"
        scroll={{ x: 'max-content' }}
        pagination={false}
        rowKey={(row, index) => String(row.id ?? row.userid ?? row.external_userid ?? row.chat_id ?? index)}
        dataSource={rows}
        columns={keys.map((key) => ({
          title: key,
          dataIndex: key,
          key,
          ellipsis: true,
          render: display,
        }))}
      />
    );
  }
  return (
    <Descriptions size="small" bordered column={{ xs: 1, sm: 2 }}>
      {Object.entries(data).slice(0, 24).map(([key, value]) => (
        <Descriptions.Item key={key} label={key}>{display(value)}</Descriptions.Item>
      ))}
    </Descriptions>
  );
}
