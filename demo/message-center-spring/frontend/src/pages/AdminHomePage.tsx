import { Alert, Button, Card, Col, Row, Space, Statistic, Tag, Typography } from 'antd';
import { ApiOutlined, AuditOutlined, PhoneOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { fetchAdminWhatsAppOverview } from '../api/endpoints';
import { useNavigate } from 'react-router-dom';

const { Title, Text } = Typography;

export default function AdminHomePage() {
  const navigate = useNavigate();
  const overview = useQuery({ queryKey: ['admin-whatsapp-overview'], queryFn: fetchAdminWhatsAppOverview, retry: false });
  const scopes = overview.data?.scopes ?? [];
  const ready = scopes.filter((scope) => scope.status === 'READY').length;
  const failures = scopes.filter((scope) => scope.lastTestStatus === 'FAILED' || scope.lastSyncStatus === 'FAILED').length;
  return <div style={{ maxWidth: 1180, margin: '0 auto', padding: 24 }}>
    <Title level={3}>管理员工作台</Title>
    <Text type="secondary">平台接入、WhatsApp 账号与模板审批</Text>
    {overview.isError ? <Alert style={{ marginTop: 20 }} type="error" showIcon message="管理员数据加载失败" /> : null}
    <Row gutter={[16, 16]} style={{ marginTop: 24 }}><Col xs={24} sm={6}><Card><Statistic title="CAMS 平台" value={scopes.length} prefix={<ApiOutlined />} suffix={<Text type="secondary">个</Text>} /></Card></Col><Col xs={24} sm={6}><Card><Statistic title="可用平台" value={ready} prefix={<SafetyCertificateOutlined />} suffix={<Text type="secondary">个</Text>} /></Card></Col><Col xs={24} sm={6}><Card><Statistic title="待审批" value={overview.data?.totalPendingApprovalCount ?? 0} prefix={<AuditOutlined />} suffix={<Text type="secondary">项</Text>} /></Card></Col><Col xs={24} sm={6}><Card><Statistic title="需要关注" value={failures} prefix={<AuditOutlined />} suffix={<Text type="secondary">个</Text>} /></Card></Col></Row>
    <Card title="平台状态" style={{ marginTop: 24 }} extra={<Button type="link" onClick={() => navigate('/admin/platforms')}>管理平台</Button>}>
      <Space direction="vertical" style={{ width: '100%' }}>{scopes.length ? scopes.map((scope) => <Space key={scope.scopeId} style={{ width: '100%', justifyContent: 'space-between' }} wrap><Space><ApiOutlined /><Text strong>{scope.displayName}</Text><Text type="secondary">{scope.custSpaceId}</Text><Tag color={scope.status === 'READY' ? 'green' : 'default'}>{scope.status === 'READY' ? '可用' : '已停用'}</Tag><Text type="secondary">{`可用号码 ${scope.usableAccountCount}`}</Text><Text type="secondary">{`待审批 ${scope.pendingApprovalCount}`}</Text><Text type="secondary">{scope.lastSyncedAt ? `最近同步 ${new Date(scope.lastSyncedAt).toLocaleString('zh-CN')}` : '尚未同步'}</Text>{scope.lastSyncStatus === 'FAILED' ? <Tag color="red">{`同步失败${scope.lastSyncErrorCode ? `：${scope.lastSyncErrorCode}` : ''}`}</Tag> : null}{scope.lastTestStatus === 'FAILED' ? <Tag color="red">{`连接测试失败${scope.lastTestErrorCode ? `：${scope.lastTestErrorCode}` : ''}`}</Tag> : null}</Space></Space>) : <Text type="secondary">暂无 CAMS 配置</Text>}</Space>
    </Card>
    <Row gutter={[16, 16]} style={{ marginTop: 24 }}><Col xs={24} md={12}><Card title="WhatsApp 账号" extra={<Button type="link" onClick={() => navigate('/admin/whatsapp/accounts')}>管理账号</Button>}><Space><PhoneOutlined /><Text type="secondary">按 CAMS 选择号码并分配给销售</Text></Space></Card></Col><Col xs={24} md={12}><Card title="模板审批" extra={<Button type="link" onClick={() => navigate('/admin/whatsapp/template-approvals')}>进入审批</Button>}><Space><AuditOutlined /><Text type="secondary">查看共享模板变更申请</Text></Space></Card></Col></Row>
  </div>;
}
